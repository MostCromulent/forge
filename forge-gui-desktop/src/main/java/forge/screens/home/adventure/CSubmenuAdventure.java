package forge.screens.home.adventure;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenu;

import forge.adventure.AdventureDesktopLauncher;
import forge.gui.framework.ICDoc;
import forge.gui.util.SOptionPane;
import forge.localinstance.skin.FSkinProp;
import forge.menus.IMenuProvider;
import forge.menus.MenuUtil;
import forge.util.Localizer;

/**
 * Controller for the Adventure Mode submenu.
 * Handles launching Adventure Mode in a separate LWJGL window.
 */
public enum CSubmenuAdventure implements ICDoc, IMenuProvider {
    SINGLETON_INSTANCE;

    private final VSubmenuAdventure view = VSubmenuAdventure.SINGLETON_INSTANCE;

    @Override
    public void register() {
    }

    @Override
    public void initialize() {
        view.getBtnStart().addActionListener(e -> launchAdventureMode());
    }

    @Override
    public void update() {
        MenuUtil.setMenuProvider(this);
        // Update button state based on whether Adventure is already running
        if (AdventureDesktopLauncher.isRunning()) {
            view.getBtnStart().setText(Localizer.getInstance().getMessage("lblAdventureRunning"));
            view.getBtnStart().setEnabled(false);
        } else {
            view.getBtnStart().setText(Localizer.getInstance().getMessage("lblStartAdventure"));
            view.getBtnStart().setEnabled(true);
        }
    }

    @Override
    public List<JMenu> getMenus() {
        return new ArrayList<>();
    }

    /**
     * Launches Adventure Mode in a separate LWJGL window.
     */
    private void launchAdventureMode() {
        if (AdventureDesktopLauncher.isRunning()) {
            SOptionPane.showMessageDialog(
                Localizer.getInstance().getMessage("lblAdventureAlreadyRunning"),
                Localizer.getInstance().getMessage("lblAdventureMode"),
                FSkinProp.ICO_WARNING);
            return;
        }

        // Launch Adventure Mode
        AdventureDesktopLauncher.launch();

        // Update button state
        view.getBtnStart().setText(Localizer.getInstance().getMessage("lblAdventureRunning"));
        view.getBtnStart().setEnabled(false);
    }
}
