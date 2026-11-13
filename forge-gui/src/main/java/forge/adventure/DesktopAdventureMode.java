package forge.adventure;

/** Whether adventure hands its battles to the web server it started, and how adventure brings its window back afterwards. */
public final class DesktopAdventureMode {
    // Cleared from the thread that sees the web server exit
    private static volatile boolean active = false;
    private static Runnable focusRequest;

    private DesktopAdventureMode() {}

    public static boolean isActive() {
        return active;
    }

    public static void activate() {
        active = true;
    }

    public static void deactivate() {
        active = false;
    }

    public static void setFocusRequest(Runnable r) {
        focusRequest = r;
    }

    public static void requestFocus() {
        if (focusRequest != null) {
            focusRequest.run();
        }
    }
}
