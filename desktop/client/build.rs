//! Builds the ffmpeg glue (src/decoder.c) and copies ffmpeg's DLLs next to the program.
//!
//! ffmpeg comes from the folder in `FFMPEG_DIR`, or else the one `get-ffmpeg.ps1` unpacks
//! into `ffmpeg/`: a shared build with its `include`, `lib` and `bin` folders.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

/// The ffmpeg libraries the decoder needs.
const LIBS: [&str; 2] = ["avcodec", "avutil"];

fn main() {
    println!("cargo:rerun-if-changed=src/decoder.c");
    println!("cargo:rerun-if-env-changed=FFMPEG_DIR");
    let ffmpeg = ffmpeg_dir();

    cc::Build::new().file("src/decoder.c").include(ffmpeg.join("include")).warnings(true).compile("lumideck_decoder");

    println!("cargo:rustc-link-search=native={}", ffmpeg.join("lib").display());
    for lib in LIBS {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }
    println!("cargo:rustc-link-lib=d3d11");

    // target/<profile>, where the program ends up: three levels above OUT_DIR.
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let target = out.ancestors().nth(3).unwrap();
    for entry in fs::read_dir(ffmpeg.join("bin")).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        let wanted = LIBS.iter().any(|lib| name.starts_with(&format!("{lib}-")))
            // avcodec loads swresample on some builds.
            || name.starts_with("swresample-");
        if wanted && name.ends_with(".dll") {
            fs::copy(&path, target.join(&name)).unwrap();
        }
    }
}

fn ffmpeg_dir() -> PathBuf {
    if let Ok(dir) = env::var("FFMPEG_DIR") {
        return PathBuf::from(dir);
    }
    // The single folder inside ffmpeg/, as unpacked from the release zip.
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("ffmpeg");
    let found = fs::read_dir(&root)
        .ok()
        .and_then(|entries| entries.filter_map(Result::ok).map(|e| e.path()).find(|p| p.join("include").is_dir()));
    match found {
        Some(dir) => dir,
        None => {
            panic!("ffmpeg not found. Run desktop\\client\\get-ffmpeg.ps1, or set FFMPEG_DIR to a shared ffmpeg build.")
        }
    }
}
