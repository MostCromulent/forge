package forge.screens.home.adventure;

import javax.swing.JPanel;

import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.screens.home.EMenuGroup;
import forge.screens.home.IVSubmenu;
import forge.screens.home.StartButton;
import forge.screens.home.VHomeUI;
import forge.toolbox.FLabel;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

/**
 * Submenu for launching Adventure Mode from the desktop GUI.
 * Adventure runs in a separate LWJGL window while battles use the desktop Swing UI.
 */
public enum VSubmenuAdventure implements IVSubmenu<CSubmenuAdventure> {
    SINGLETON_INSTANCE;

    private DragCell parentCell;
    final Localizer localizer = Localizer.getInstance();
    private final DragTab tab = new DragTab(localizer.getMessage("lblAdventureMode"));
    private final StartButton btnStart = new StartButton();
    private final FLabel lblDescription = new FLabel.Builder()
            .text("<html><div style='text-align:center'>" +
                  "Adventure Mode lets you explore a fantasy world, " +
                  "collect cards, build decks, and battle enemies.<br><br>" +
                  "Battles will use the desktop interface for the best experience." +
                  "</div></html>")
            .fontSize(14)
            .build();

    VSubmenuAdventure() {
        btnStart.setText(localizer.getMessage("lblStartAdventure"));
    }

    @Override
    public EMenuGroup getGroupEnum() {
        return EMenuGroup.ADVENTURE;
    }

    @Override
    public String getMenuTitle() {
        return localizer.getMessage("lblAdventure");
    }

    @Override
    public EDocID getItemEnum() {
        return EDocID.HOME_ADVENTURE;
    }

    @Override
    public EDocID getDocumentID() {
        return EDocID.HOME_ADVENTURE;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CSubmenuAdventure getLayoutControl() {
        return CSubmenuAdventure.SINGLETON_INSTANCE;
    }

    @Override
    public void setParentCell(DragCell cell0) {
        this.parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return this.parentCell;
    }

    public StartButton getBtnStart() {
        return btnStart;
    }

    @Override
    public void populate() {
        final JPanel container = VHomeUI.SINGLETON_INSTANCE.getPnlDisplay();

        container.removeAll();
        container.setLayout(new MigLayout("insets 0, gap 0, wrap 1, ax center, ay center"));

        FLabel lblTitle = new FLabel.Builder()
                .text(localizer.getMessage("lblAdventureMode"))
                .fontSize(20)
                .build();

        container.add(lblTitle, "w 80%, h 40px!, gap 0 0 40px 20px, al center");
        container.add(lblDescription, "w 60%, h 100px!, gap 0 0 20px 20px, al center");
        container.add(btnStart, "w 300px!, h 50px!, gap 0 0 30px 0, al center");

        if (container.isShowing()) {
            container.validate();
            container.repaint();
        }
    }
}
