package app.lumideck.helper;

import android.graphics.Rect;

/** The window helper (WindowHelper.kt), as LumiDeck calls it. */
interface IWindowHelper {
    /** Bump when this interface changes, so an older helper still running is turned away. */
    const int VERSION = 2;

    oneway void focus(int taskId);
    oneway void minimize(int taskId);
    oneway void resize(int taskId, in Rect bounds);
    oneway void close(int taskId);
    /** A newer helper has taken over. */
    oneway void exit();
}
