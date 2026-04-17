package forge.screens.home.online;

import forge.game.GameType;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.itemmanager.DeckManager;
import forge.itemmanager.ItemManagerContainer;
import forge.screens.deckeditor.CDeckEditorUI;
import forge.screens.home.EMenuGroup;
import forge.screens.home.IVSubmenu;
import forge.screens.home.LblHeader;
import forge.screens.home.VHomeUI;
import forge.screens.home.VHomeUI.PnlDisplay;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

/**
 * Assembles Swing components of the online event decks submenu singleton.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public enum VSubmenuOnlineDecks implements IVSubmenu<CSubmenuOnlineDecks> {
    /** */
    SINGLETON_INSTANCE;

    // Fields used with interface IVDoc
    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblNetEventDecks"));

    private final LblHeader lblTitle = new LblHeader(Localizer.getInstance().getMessage("lblNetEventDecks"));

    private final DeckManager lstDecks = new DeckManager(GameType.Sealed, CDeckEditorUI.SINGLETON_INSTANCE.getCDetailPicture());

    VSubmenuOnlineDecks() {
        lblTitle.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));
    }

    /* (non-Javadoc)
     * @see forge.view.home.IViewSubmenu#getGroup()
     */
    @Override
    public EMenuGroup getGroupEnum() {
        return EMenuGroup.ONLINE;
    }

    /* (non-Javadoc)
     * @see forge.gui.home.IVSubmenu#getMenuTitle()
     */
    @Override
    public String getMenuTitle() {
        return Localizer.getInstance().getMessage("lblNetEventDecks");
    }

    /* (non-Javadoc)
     * @see forge.gui.home.IVSubmenu#getItemEnum()
     */
    @Override
    public EDocID getItemEnum() {
        return EDocID.HOME_NET_DECKS;
    }

    /** @return {@link forge.itemmanager.DeckManager} */
    public DeckManager getLstDecks() {
        return lstDecks;
    }

    //========== Overridden from IVDoc

    /* (non-Javadoc)
     * @see forge.view.home.IViewSubmenu#populate()
     */
    @Override
    public void populate() {
        PnlDisplay pnlDisplay = VHomeUI.SINGLETON_INSTANCE.getPnlDisplay();
        pnlDisplay.removeAll();
        pnlDisplay.setLayout(new MigLayout("insets 0, gap 0, wrap, ax right"));
        pnlDisplay.add(lblTitle, "w 80%!, h 40px!, gap 0 0 15px 15px, ax right");
        pnlDisplay.add(new ItemManagerContainer(lstDecks), "w 80%!, gap 0 10% 0 30px, pushy, growy");

        pnlDisplay.repaint();
        pnlDisplay.revalidate();
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getDocumentID()
     */
    @Override
    public EDocID getDocumentID() {
        return EDocID.HOME_NET_DECKS;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getTabLabel()
     */
    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#setParentCell(forge.gui.framework.DragCell)
     */
    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getParentCell()
     */
    @Override
    public DragCell getParentCell() {
        return parentCell;
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVDoc#getLayoutControl()
     */
    @Override
    public CSubmenuOnlineDecks getLayoutControl() {
        return CSubmenuOnlineDecks.SINGLETON_INSTANCE;
    }
}
