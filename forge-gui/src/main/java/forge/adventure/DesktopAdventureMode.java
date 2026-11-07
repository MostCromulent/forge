package forge.adventure;

/**
 * Owns the activation state of the experimental Desktop Adventure Mode. Both
 * the desktop process (which spawns Adventure) and the mobile process (which
 * runs Adventure) consult this class to decide whether the IPC battle handoff
 * should be used.
 */
public final class DesktopAdventureMode {
    private static boolean active = false;
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
