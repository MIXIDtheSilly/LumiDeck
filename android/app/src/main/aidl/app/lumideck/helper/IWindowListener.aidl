package app.lumideck.helper;

import app.lumideck.helper.TaskWindow;

/** LumiDeck, as the window helper calls it. */
oneway interface IWindowListener {
    /** Every app window on every display, oldest first. */
    void onWindowsChanged(in List<TaskWindow> windows);
}
