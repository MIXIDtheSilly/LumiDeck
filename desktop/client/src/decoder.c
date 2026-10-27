// The video decoder: ffmpeg decoding H.264 or HEVC on the GPU (D3D11VA) into textures on
// the client's own D3D11 device. A thin C layer so the Rust side never depends on the
// layout of ffmpeg's structs, which changes between versions. See src/decoder.rs.

#define COBJMACROS
#include <d3d11.h>

#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/log.h>

typedef struct LdDecoder {
    AVCodecContext *ctx;
    AVPacket *packet;
    AVFrame *frame;
} LdDecoder;

typedef struct LdFrame {
    ID3D11Texture2D *texture;
    int index;
    int width;
    int height;
    int full_range;
    int bt709;
    void *handle;
} LdFrame;

static enum AVPixelFormat pick_d3d11(AVCodecContext *ctx, const enum AVPixelFormat *formats) {
    (void)ctx;
    for (; *formats != AV_PIX_FMT_NONE; formats++) {
        if (*formats == AV_PIX_FMT_D3D11) return *formats;
    }
    return AV_PIX_FMT_NONE;
}

static void set_error(char *error, int size, const char *what, int code) {
    char reason[128] = "";
    if (code) av_strerror(code, reason, sizeof reason);
    snprintf(error, size, "%s%s%s", what, code ? ": " : "", reason);
}

void ld_decoder_free(LdDecoder *d) {
    if (!d) return;
    avcodec_free_context(&d->ctx);
    av_packet_free(&d->packet);
    av_frame_free(&d->frame);
    av_free(d);
}

LdDecoder *ld_decoder_new(int hevc, ID3D11Device *device, char *error, int error_size) {
    // Errors only: the device's encoder adds header units ffmpeg skips with a warning.
    av_log_set_level(AV_LOG_ERROR);
    const AVCodec *codec = avcodec_find_decoder(hevc ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
    if (!codec) {
        set_error(error, error_size, "ffmpeg has no decoder for this codec", 0);
        return NULL;
    }
    LdDecoder *d = av_mallocz(sizeof *d);
    d->ctx = avcodec_alloc_context3(codec);
    d->packet = av_packet_alloc();
    d->frame = av_frame_alloc();
    if (!d->ctx || !d->packet || !d->frame) {
        set_error(error, error_size, "Out of memory", 0);
        ld_decoder_free(d);
        return NULL;
    }

    AVBufferRef *hw = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
    AVD3D11VADeviceContext *d3d = (AVD3D11VADeviceContext *)((AVHWDeviceContext *)hw->data)->hwctx;
    ID3D11Device_AddRef(device);
    d3d->device = device;
    int r = av_hwdevice_ctx_init(hw);
    if (r < 0) {
        av_buffer_unref(&hw);
        set_error(error, error_size, "The GPU can't decode video", r);
        ld_decoder_free(d);
        return NULL;
    }
    d->ctx->hw_device_ctx = hw;
    d->ctx->get_format = pick_d3d11;
    // Every frame out as soon as it's in: the stream has no B-frames to wait for.
    d->ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    d->ctx->thread_count = 1;
    // The last frame stays on screen (and in use) while the next ones decode.
    d->ctx->extra_hw_frames = 4;
    r = avcodec_open2(d->ctx, codec, NULL);
    if (r < 0) {
        set_error(error, error_size, "Couldn't start the decoder", r);
        ld_decoder_free(d);
        return NULL;
    }
    return d;
}

// Returns 0, or a negative ffmpeg error.
int ld_decoder_send(LdDecoder *d, const uint8_t *data, int size) {
    d->packet->data = (uint8_t *)data;
    d->packet->size = size;
    int r = avcodec_send_packet(d->ctx, d->packet);
    av_packet_unref(d->packet);
    return r;
}

// Returns 1 with a frame in *out (free it with ld_frame_free), 0 if none is ready, or a
// negative ffmpeg error.
int ld_decoder_receive(LdDecoder *d, LdFrame *out) {
    int r = avcodec_receive_frame(d->ctx, d->frame);
    if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return 0;
    if (r < 0) return r;
    if (d->frame->format != AV_PIX_FMT_D3D11) {
        av_frame_unref(d->frame);
        return AVERROR(ENOSYS);
    }
    AVFrame *frame = av_frame_clone(d->frame);
    av_frame_unref(d->frame);
    if (!frame) return AVERROR(ENOMEM);
    out->texture = (ID3D11Texture2D *)frame->data[0];
    out->index = (int)(intptr_t)frame->data[1];
    out->width = frame->width;
    out->height = frame->height;
    out->full_range = frame->color_range == AVCOL_RANGE_JPEG;
    // HD video is BT.709 unless it says otherwise.
    out->bt709 = frame->colorspace != AVCOL_SPC_BT470BG && frame->colorspace != AVCOL_SPC_SMPTE170M;
    out->handle = frame;
    return 1;
}

void ld_frame_free(void *handle) {
    AVFrame *frame = handle;
    av_frame_free(&frame);
}

void ld_error_string(int code, char *buffer, int size) {
    av_strerror(code, buffer, size);
}
