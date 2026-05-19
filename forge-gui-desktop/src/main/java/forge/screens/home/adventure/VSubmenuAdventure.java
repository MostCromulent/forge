package forge.screens.home.adventure;

import javax.swing.JPanel;

import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.screens.home.EMenuGroup;
import forge.screens.home.IVSubmenu;
import forge.screens.home.VHomeUI;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FSkin;
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
    private final FButton btnStart = new FButton(localizer.getMessage("lblStartAdventure"));
    private final FLabel lblDescription = new FLabel.Builder()
            .text("<html><div style='text-align:center'>" +
                  localizer.getMessage("lblAdventureModeDescPara1") + "<br><br>" +
                  localizer.getMessage("lblAdventureModeDescPara2") + "<br><br>" +
                  "<b>" + localizer.getMessage("lblAdventureModeDescPara3Warning") + "</b>" +
                  "</div></html>")
            .fontSize(14)
            .build();

    VSubmenuAdventure() {
        btnStart.setFont(FSkin.getRelativeFont(20));
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

    public FButton getBtnStart() {
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
        container.add(lblDescription, "w 60%, h 220px!, gap 0 0 20px 20px, al center");
        container.add(btnStart, "w 300!, h 75!, gap 0 0 30px 0, al center");

        if (container.isShowing()) {
            container.validate();
            container.repaint();
        }
    }
}
