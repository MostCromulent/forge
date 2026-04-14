package forge.screens.deckeditor.controllers;

import forge.Singletons;
import forge.deck.Deck;
import forge.game.GameType;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gui.FDraftOverlay;
import forge.gui.framework.DragCell;
import forge.gui.framework.FScreen;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.itemmanager.ItemManagerConfig;
import forge.model.FModel;
import forge.screens.deckeditor.CDeckEditorUI;
import forge.screens.deckeditor.views.VAllDecks;
import forge.screens.deckeditor.views.VBrawlDecks;
import forge.screens.deckeditor.views.VCardCatalog;
import forge.screens.deckeditor.views.VCommanderDecks;
import forge.screens.deckeditor.views.VCurrentDeck;
import forge.screens.deckeditor.views.VDeckgen;
import forge.screens.deckeditor.views.VEditorLog;
import forge.screens.deckeditor.views.VOathbreakerDecks;
import forge.screens.deckeditor.views.VTinyLeadersDecks;
import forge.screens.match.controllers.CDetailPicture;
import forge.toolbox.FOptionPane;
import forge.util.ItemPool;
import forge.util.Localizer;

import java.util.List;
import java.util.Map.Entry;
import java.util.function.Consumer;

/**
 * Network draft editor controller. Works with a push model: packs arrive
 * via {@link forge.gamemodes.net.event.DraftPackArrivedEvent} and picks
 * are sent back via a {@link Consumer} callback.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 */
public class CEditorNetworkDraft extends ACEditorBase<PaperCard, Deck> {

    private final int seatIndex;
    private final String eventId;
    private final Consumer<DraftPickEvent> pickSender;
    private final Localizer localizer = Localizer.getInstance();

    private int currentPackNumber;
    private int currentPickNumber;
    private boolean draftComplete;

    // Saved tab parents for cleanup
    private String ccAddLabel;
    private DragCell constructedDecksParent;
    private DragCell commanderDecksParent;
    private DragCell oathbreakerDecksParent;
    private DragCell brawlDecksParent;
    private DragCell tinyLeadersDecksParent;
    private DragCell deckGenParent;

    /**
     * @param seatIndex   this player's seat in the draft pod
     * @param eventId     the network event ID
     * @param pickSender  callback to send picks; for the host this calls
     *                    ServerGameLobby.handleDraftPick directly, for
     *                    clients it sends via FGameClient
     * @param cDetailPicture0 the shared detail picture controller
     */
    public CEditorNetworkDraft(int seatIndex, String eventId,
            Consumer<DraftPickEvent> pickSender, CDetailPicture cDetailPicture0) {
        super(FScreen.DRAFTING_PROCESS, cDetailPicture0, GameType.Draft);

        this.seatIndex = seatIndex;
        this.eventId = eventId;
        this.pickSender = pickSender;

        final CardManager catalogManager = new CardManager(cDetailPicture0, false, false, true);
        final CardManager deckManager = new CardManager(cDetailPicture0, false, false, true);

        // Hide filters so more of the pack is visible
        catalogManager.setHideViewOptions(1, true);

        deckManager.setCaption(localizer.getMessage("lblDraftPicks"));

        catalogManager.setAlwaysNonUnique(true);
        deckManager.setAlwaysNonUnique(true);

        this.setCatalogManager(catalogManager);
        this.setDeckManager(deckManager);
    }

    /**
     * Initialize the drafting screen. Called once before the first pack arrives.
     */
    public void showGui() {
        VEditorLog.SINGLETON_INSTANCE.resetNewDraft();
    }

    /**
     * Display a new pack for the player to pick from.
     *
     * @param pack       the cards in the pack
     * @param packNumber 1-based pack number
     * @param pickNumber 0-based pick number within the pack round
     */
    public void showPack(List<PaperCard> pack, int packNumber, int pickNumber) {
        this.currentPackNumber = packNumber;
        this.currentPickNumber = pickNumber;

        ItemPool<PaperCard> pool = new ItemPool<>(PaperCard.class);
        for (PaperCard card : pack) {
            pool.add(card, 1);
        }

        this.getCatalogManager().setCaption(
                localizer.getMessage("lblPackNCards", String.valueOf(packNumber)));
        this.getCatalogManager().setPool(pool);
        this.getCatalogManager().refresh();
    }

    @Override
    protected void onAddItems(Iterable<Entry<PaperCard, Integer>> items, boolean toAlternate) {
        if (toAlternate || draftComplete) {
            return;
        }

        // Draft one card at a time
        PaperCard card = items.iterator().next().getKey();

        // Add to the picks pool
        this.getDeckManager().addItem(card, 1);

        // Send pick to server
        pickSender.accept(new DraftPickEvent(seatIndex, card));

        // Log the pick
        NetworkDraftLog.logMyPick(card.getName(), currentPickNumber);

        // Clear the catalog — waiting for next pack
        this.getCatalogManager().setPool((Iterable<PaperCard>) null);

        // Notify the overlay
        FDraftOverlay.SINGLETON_INSTANCE.onPickSubmitted();
    }

    @Override
    protected void onRemoveItems(Iterable<Entry<PaperCard, Integer>> items, boolean toAlternate) {
        // Cannot remove cards during draft
    }

    @Override
    protected void buildAddContextMenu(EditorContextMenuBuilder cmb) {
        cmb.addMoveItems(localizer.getMessage("lblDraft"), null);
    }

    @Override
    protected void buildRemoveContextMenu(EditorContextMenuBuilder cmb) {
        // No valid remove options during draft
    }

    /**
     * Called when the draft is complete and the pool arrives from the server.
     *
     * @param pool the final deck group containing the drafted pool
     */
    public void completeDraft(Deck pool) {
        draftComplete = true;

        // Save the pool
        FModel.getDecks().getNetworkEventDecks().add(pool);

        // Log completion
        int totalCards = 0;
        if (getDeckManager().getPool() != null) {
            totalCards = getDeckManager().getPool().countAll();
        }
        NetworkDraftLog.logDraftComplete(totalCards);

        // Reset the overlay
        FDraftOverlay.SINGLETON_INSTANCE.reset();

        // Close the drafting screen and open the deck editor
        FScreen.DRAFTING_PROCESS.close();

        // Open the pool in the limited deck editor
        FScreen editScreen = FScreen.DECK_EDITOR_SEALED;
        CEditorLimited<Deck> editorCtrl = new CEditorLimited<>(
                FModel.getDecks().getNetworkEventDecks(), Deck::new, editScreen, getCDetailPicture());
        Singletons.getControl().setCurrentScreen(editScreen);
        CDeckEditorUI.SINGLETON_INSTANCE.setEditorController(editorCtrl);
        editorCtrl.getDeckController().load(null, pool.getName());

        FOptionPane.showMessageDialog("Draft complete! Your pool has been saved as '"
                + pool.getName() + "'.");
    }

    @Override
    protected CardLimit getCardLimit() {
        return CardLimit.None;
    }

    @Override
    public DeckController<Deck> getDeckController() {
        return null;
    }

    @Override
    public void resetTables() {
    }

    @Override
    public void update() {
        this.getCatalogManager().setup(ItemManagerConfig.DRAFT_PACK);
        this.getDeckManager().setup(ItemManagerConfig.DRAFT_POOL);

        if (VEditorLog.SINGLETON_INSTANCE.getParentCell() == null) {
            VCardCatalog.SINGLETON_INSTANCE.getParentCell().addDoc(VEditorLog.SINGLETON_INSTANCE);
            VEditorLog.SINGLETON_INSTANCE.showView();
        }

        ccAddLabel = this.getBtnAdd().getText();

        // Start with an empty catalog — packs arrive via events
        if (this.getDeckManager().getPool() == null) {
            this.getDeckManager().setPool((Iterable<PaperCard>) null);
        }

        // Hide buttons not used in draft mode
        this.getBtnAdd().setVisible(false);
        this.getBtnAdd4().setVisible(false);
        this.getBtnRemove().setVisible(false);
        this.getBtnRemove4().setVisible(false);

        this.getCbxSection().setVisible(false);

        VCurrentDeck.SINGLETON_INSTANCE.getPnlHeader().setVisible(false);

        deckGenParent = removeTab(VDeckgen.SINGLETON_INSTANCE);
        constructedDecksParent = removeTab(VAllDecks.SINGLETON_INSTANCE);
        commanderDecksParent = removeTab(VCommanderDecks.SINGLETON_INSTANCE);
        oathbreakerDecksParent = removeTab(VOathbreakerDecks.SINGLETON_INSTANCE);
        brawlDecksParent = removeTab(VBrawlDecks.SINGLETON_INSTANCE);
        tinyLeadersDecksParent = removeTab(VTinyLeadersDecks.SINGLETON_INSTANCE);

        // Single-selection only
        getCatalogManager().setAllowMultipleSelections(false);

        getCatalogManager().refresh();
    }

    @Override
    public boolean canSwitchAway(boolean isClosing) {
        if (isClosing && !draftComplete) {
            String userPrompt = localizer.getMessage("lblEndDraftConfirm");
            return FOptionPane.showConfirmDialog(userPrompt,
                    localizer.getMessage("lblLeaveDraft"),
                    localizer.getMessage("lblLeave"),
                    localizer.getMessage("lblCancel"), false);
        }
        return true;
    }

    @Override
    public void resetUIChanges() {
        // Re-rename buttons
        this.getBtnAdd().setText(ccAddLabel);

        // Re-add buttons
        this.getBtnAdd4().setVisible(true);
        this.getBtnRemove().setVisible(true);
        this.getBtnRemove4().setVisible(true);

        VCurrentDeck.SINGLETON_INSTANCE.getPnlHeader().setVisible(true);
        VEditorLog.SINGLETON_INSTANCE.getParentCell().setVisible(true);

        // Re-add tabs
        if (deckGenParent != null) {
            deckGenParent.addDoc(VDeckgen.SINGLETON_INSTANCE);
        }
        if (constructedDecksParent != null) {
            constructedDecksParent.addDoc(VAllDecks.SINGLETON_INSTANCE);
        }
        if (commanderDecksParent != null) {
            commanderDecksParent.addDoc(VCommanderDecks.SINGLETON_INSTANCE);
        }
        if (oathbreakerDecksParent != null) {
            oathbreakerDecksParent.addDoc(VOathbreakerDecks.SINGLETON_INSTANCE);
        }
        if (brawlDecksParent != null) {
            brawlDecksParent.addDoc(VBrawlDecks.SINGLETON_INSTANCE);
        }
        if (tinyLeadersDecksParent != null) {
            tinyLeadersDecksParent.addDoc(VTinyLeadersDecks.SINGLETON_INSTANCE);
        }

        // Restore multi-selection
        getCatalogManager().setAllowMultipleSelections(true);
    }
}
