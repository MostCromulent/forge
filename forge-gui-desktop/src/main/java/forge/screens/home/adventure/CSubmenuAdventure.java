package forge.screens.home.adventure;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenu;

import forge.adventure.DesktopAdventureLauncher;
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
        view.getBtnStart().setEnabled(!DesktopAdventureLauncher.isRunning());
    }

    @Override
    public List<JMenu> getMenus() {
        return new ArrayList<>();
    }

    /**
     * Launches Adventure Mode in a separate LWJGL window.
     */
    private void launchAdventureMode() {
        if (DesktopAdventureLauncher.isRunning()) {
            SOptionPane.showMessageDialog(
                Localizer.getInstance().getMessage("lblAdventureAlreadyRunning"),
                Localizer.getInstance().getMessage("lblAdventureMode"),
                FSkinProp.ICO_WARNING);
            return;
        }

        DesktopAdventureLauncher.launch();
        view.getBtnStart().setEnabled(false);
    }
}
