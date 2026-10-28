package app.lumideck.helper;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;

/** The window helper (WindowHelper.kt), as LumiDeck calls it. */
interface IWindowHelper {
    /** Bump when this interface changes, so an older helper still running is turned away. */
    const int VERSION = 4;

    /** Starts [intent] for [user] with [options] (ActivityOptions), without Android's opening animation. */
    oneway void launch(in Intent intent, in Bundle options, in UserHandle user);
    oneway void focus(int taskId);
    oneway void minimize(int taskId);
    oneway void resize(int taskId, in Rect bounds);
    oneway void close(int taskId);
    /** Turns Wi-Fi on or off, which apps may no longer do. */
    oneway void setWifi(boolean on);
    oneway void setBluetooth(boolean on);
    /** A newer helper has taken over. */
    oneway void exit();
}
