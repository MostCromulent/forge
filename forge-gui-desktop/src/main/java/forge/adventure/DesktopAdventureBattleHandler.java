package forge.adventure;

/**
 * Handles tracking of Adventure Mode state when launched from Desktop.
 * Since Adventure runs as a separate process, this class primarily
 * tracks whether Adventure is running.
 */
public class DesktopAdventureBattleHandler {

    /**
     * @return true if Adventure mode is currently running
     */
    public static boolean isAdventureRunning() {
        return AdventureDesktopLauncher.isRunning();
    }

    /**
     * @return true if desktop adventure mode flag is set
     */
    public static boolean isDesktopAdventureMode() {
        return DesktopAdventureMode.isActive();
    }
}
