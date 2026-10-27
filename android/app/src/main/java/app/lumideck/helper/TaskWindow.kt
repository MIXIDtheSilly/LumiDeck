package app.lumideck.helper

import android.content.ComponentName
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable

/** An app window: an Android task, as the window helper reports it. */
data class TaskWindow(
    val taskId: Int,
    val displayId: Int,
    /** The activity that started the task, usually the app's launcher activity. */
    val component: ComponentName,
    val userId: Int,
    /** In front and getting keys. */
    val focused: Boolean,
    /** False once minimised. */
    val visible: Boolean,
    /** A resizable window, rather than fullscreen. */
    val freeform: Boolean,
    val bounds: Rect,
    /** When it was last in front, in [android.os.SystemClock.elapsedRealtime] time. */
    val lastActive: Long,
    /** Its place in the stack of windows on its display: lower is further in front. */
    val layer: Int,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(taskId)
        dest.writeInt(displayId)
        component.writeToParcel(dest, flags)
        dest.writeInt(userId)
        dest.writeBoolean(focused)
        dest.writeBoolean(visible)
        dest.writeBoolean(freeform)
        bounds.writeToParcel(dest, flags)
        dest.writeLong(lastActive)
        dest.writeInt(layer)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<TaskWindow> {
            override fun createFromParcel(source: Parcel) = TaskWindow(
                taskId = source.readInt(),
                displayId = source.readInt(),
                component = ComponentName(source),
                userId = source.readInt(),
                focused = source.readBoolean(),
                visible = source.readBoolean(),
                freeform = source.readBoolean(),
                bounds = Rect.CREATOR.createFromParcel(source),
                lastActive = source.readLong(),
                layer = source.readInt(),
            )

            override fun newArray(size: Int) = arrayOfNulls<TaskWindow>(size)
        }
    }
}
