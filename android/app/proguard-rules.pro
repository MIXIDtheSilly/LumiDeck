# The window helper's entry point, which app_process calls by name (helper/WindowHelper.kt).
-keep class app.lumideck.helper.WindowHelper {
    public static void main(java.lang.String[]);
}

# The stream server's entry point, which the PC client starts the same way (server/StreamServer.kt).
-keep class app.lumideck.server.StreamServer {
    public static void main(java.lang.String[]);
}
