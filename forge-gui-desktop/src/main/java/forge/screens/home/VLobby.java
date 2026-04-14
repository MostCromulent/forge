package forge.screens.home;

import java.awt.Font;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.ai.AIOption;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckSection;
import forge.deck.DeckType;
import forge.deck.DeckgenUtil;
import forge.deck.RandomDeckGenerator;
import forge.deckchooser.FDeckChooser;
import forge.game.GameType;
import forge.game.card.CardView;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gui.CardDetailPanel;
import forge.gui.SwingPrefBinders;
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.interfaces.IPlayerChangeListener;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.toolbox.FButton;
import forge.toolbox.FCheckBox;
import forge.toolbox.FComboBoxPanel;
import forge.toolbox.FTabbedPane;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.toolbox.FPanel;
import forge.toolbox.FScrollPane;
import forge.toolbox.FScrollPanel;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinImage;
import forge.toolbox.FTextField;
import forge.util.*;
import net.miginfocom.swing.MigLayout;

/**
 * Lobby view. View of a number of players at the deck selection stage.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public class VLobby implements ILobbyView {

    static final int MAX_PLAYERS = 8;
    final Localizer localizer = Localizer.getInstance();
    private static final ForgePreferences prefs = FModel.getPreferences();

    // General variables
    private final GameLobby lobby;
    private IPlayerChangeListener playerChangeListener = null;
    private final LblHeader lblTitle = new LblHeader(localizer.getMessage("lblHeaderConstructedMode"));
    private int activePlayersNum = 0;
    private int playerWithFocus = 0; // index of the player that currently has focus

    private final StartButton btnStart  = new StartButton();
    private final JPanel pnlStart = new JPanel(new MigLayout("insets 0, gap 0, wrap 2"));
    private final JComboBox<String> gamesInMatch = new JComboBox<String>(new String[] {"1","3","5"});
    private final SwingPrefBinders.ComboBox gamesInMatchBinder =
      new SwingPrefBinders.ComboBox(FPref.UI_MATCHES_PER_GAME, gamesInMatch);
    private final JPanel gamesInMatchFrame = new JPanel(new MigLayout("insets 0, gap 0, wrap 2"));
    private final JPanel constructedFrame = new JPanel(new MigLayout("insets 0, gap 0, wrap 2, hidemode 3")); // Main content frame

    // Variants frame and variables
    private final FPanel variantsPanel = new FPanel(new MigLayout("insets 10, gapx 10"));
    private final VariantCheckBox vntVanguard = new VariantCheckBox(GameType.Vanguard);
    private final VariantCheckBox vntMomirBasic = new VariantCheckBox(GameType.MomirBasic);
    private final VariantCheckBox vntMoJhoSto = new VariantCheckBox(GameType.MoJhoSto);
    private final VariantCheckBox vntCommander = new VariantCheckBox(GameType.Commander);
    private final VariantCheckBox vntOathbreaker = new VariantCheckBox(GameType.Oathbreaker);
    private final VariantCheckBox vntTinyLeaders = new VariantCheckBox(GameType.TinyLeaders);
    private final VariantCheckBox vntBrawl = new VariantCheckBox(GameType.Brawl);
    private final VariantCheckBox vntPlanechase = new VariantCheckBox(GameType.Planechase);
    private final VariantCheckBox vntArchenemy = new VariantCheckBox(GameType.Archenemy);
    private final VariantCheckBox vntArchenemyRumble = new VariantCheckBox(GameType.ArchenemyRumble);
    private final ImmutableList<VariantCheckBox> vntBoxesLocal  =
            ImmutableList.of(vntVanguard, vntMomirBasic, vntMoJhoSto, vntCommander, vntOathbreaker, vntBrawl, vntTinyLeaders, vntPlanechase, vntArchenemy, vntArchenemyRumble);
    private final ImmutableList<VariantCheckBox> vntBoxesNetwork =
            ImmutableList.of(vntVanguard, vntMomirBasic, vntMoJhoSto, vntCommander, vntOathbreaker, vntBrawl, vntTinyLeaders /*, vntPlanechase, vntArchenemy, vntArchenemyRumble */);

    // Player frame elements
    private final JPanel playersFrame = new JPanel(new MigLayout("insets 0, gap 0 5, wrap, hidemode 3"));
    private final FScrollPanel playersScroll = new FScrollPanel(new MigLayout("insets 0, gap 0, wrap, hidemode 3"), true);
    private final List<PlayerPanel> playerPanels = new ArrayList<>(MAX_PLAYERS);
    // Cache deck choosers so switching settings doesn't re-generate random decks.
    private final Map<FPref, FDeckChooser> cachedDeckChoosers = new HashMap<>();

    private final FLabel addPlayerBtn = new FLabel.ButtonBuilder().fontSize(14).text(localizer.getMessage("lblAddAPlayer")).build();

    // Deck frame elements
    private final JPanel decksFrame = new JPanel(new MigLayout("insets 0, gap 0, wrap, hidemode 3"));
    private final FCheckBox cbSingletons = new FCheckBox(localizer.getMessage("cbSingletons"));
    private final FCheckBox cbArtifacts = new FCheckBox(localizer.getMessage("cbRemoveArtifacts"));
    private final Deck[] decks = new Deck[MAX_PLAYERS];

    // Variants
    private final List<FList<Object>> schemeDeckLists = new ArrayList<>();
    private final List<FPanel> schemeDeckPanels = new ArrayList<>(MAX_PLAYERS);

    private final List<FList<Object>> planarDeckLists = new ArrayList<>();
    private final List<FPanel> planarDeckPanels = new ArrayList<>(MAX_PLAYERS);

    private final List<FList<Object>> vgdAvatarLists = new ArrayList<>();
    private final List<FPanel> vgdPanels = new ArrayList<>(MAX_PLAYERS);
    private final List<CardDetailPanel> vgdAvatarDetails = new ArrayList<>();
    private final List<PaperCard> vgdAllAvatars = new ArrayList<>();
    private final List<PaperCard> nonRandomHumanAvatars = new ArrayList<>();
    private final List<PaperCard> nonRandomAiAvatars = new ArrayList<>();
    private final Vector<Object> humanListData = new Vector<>();
    private final Vector<Object> aiListData = new Vector<>();

    // Mode selector (network only)
    public enum LobbyMode { CONSTRUCTED, DRAFT, SEALED }
    private LobbyMode currentMode = LobbyMode.CONSTRUCTED;
    private final FComboBoxPanel<String> cboModePanel = new FComboBoxPanel<>("Mode:",
            ImmutableList.of("Constructed", "Draft", "Sealed"));

    // Event config panel (shown as a tab in Draft/Sealed mode)
    private final FPanel eventConfigPanel = new FPanel(new MigLayout("insets 10, gap 5, wrap"));
    private final FLabel lblEventConfig = new FLabel.Builder().text("No event configured").fontSize(14).build();
    private final FLabel btnConfigure = new FLabel.ButtonBuilder().text("Configure...").fontSize(12).build();
    private final FCheckBox cbDeckConformance = new FCheckBox("Deck conformance");

    // Tabbed pane for right panel in Draft/Sealed mode
    private final FTabbedPane rightTabs = new FTabbedPane();

    // Action buttons for Draft/Sealed mode
    private final FButton btnStartEvent = new FButton("Start Draft");
    private final FButton btnStartMatch = new FButton("Start Match");

    // CTR
    public VLobby(final GameLobby lobby) {
        this.lobby = lobby;

        lblTitle.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));

        ////////////////////////////////////////////////////////
        //////////////////// Mode Selector (network only) //////
        if (lobby.isAllowNetworking()) {
            cboModePanel.addActionListener(e -> onModeChanged());
            // Set a larger font on the combo box to match/exceed the variants label
            for (final java.awt.Component c : cboModePanel.getComponents()) {
                c.setFont(FSkin.getBoldFont(14).getBaseFont());
            }
            constructedFrame.add(cboModePanel, "w 100%, h 28px!, gapbottom 2px, spanx 2, wrap");
        }

        ////////////////////////////////////////////////////////
        //////////////////// Event Config Panel (network only) /
        if (lobby.isAllowNetworking()) {
            eventConfigPanel.setOpaque(true);
            eventConfigPanel.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2).stepColor(20).getColor());
            eventConfigPanel.add(lblEventConfig, "wrap, gapbottom 10");
            if (lobby.hasControl()) {
                btnConfigure.setCommand(() -> openEventConfigDialog());
                eventConfigPanel.add(btnConfigure, "w 120px!, h 26px!, wrap, gapbottom 5");
            }
            cbDeckConformance.setSelected(true);
            cbDeckConformance.setEnabled(lobby.hasControl());
            eventConfigPanel.add(cbDeckConformance, "wrap");

            // Set tab font larger for readability
            rightTabs.setFont(FSkin.getBoldFont(14).getBaseFont());
        }

        ////////////////////////////////////////////////////////
        //////////////////// Variants Panel ////////////////////
        ImmutableList<VariantCheckBox> vntBoxes = null;
        if (lobby.isAllowNetworking()) {
            vntBoxes = vntBoxesNetwork;
        } else {
            vntBoxes = vntBoxesLocal;
        }

        variantsPanel.setOpaque(false);
        variantsPanel.add(newLabel(localizer.getMessage("lblVariants")));
        for (final VariantCheckBox vcb : vntBoxes) {
            variantsPanel.add(vcb);
        }

        constructedFrame.add(new FScrollPane(variantsPanel, false, true,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED),
                "w 100%, h 45px!, gapbottom 10px, spanx 2, wrap");

        playersFrame.setOpaque(false);
        playersFrame.add(playersScroll, "w 100%, h 100%-35px");

        if (lobby.hasControl()) {
            addPlayerBtn.setFocusable(true);
            addPlayerBtn.setCommand(lobby::addSlot);
            playersFrame.add(addPlayerBtn, "height 30px!, growx, pushx");
        }

        constructedFrame.add(playersFrame, "gapright 10px, w 50%-5px, growy, pushy");

        ////////////////////////////////////////////////////////
        ////////////////////// Deck Panel //////////////////////

        populateVanguardLists();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            buildDeckPanels(i);
        }
        constructedFrame.add(decksFrame, "w 50%-5px, growy, pushy");
        constructedFrame.setOpaque(false);
        decksFrame.setOpaque(false);

        // Start Button
        if (lobby.hasControl()) {
            pnlStart.setOpaque(false);
            pnlStart.add(btnStart, "align center");
            // Start button event handling
            btnStart.addActionListener(arg0 -> {
                Runnable startGame = lobby.startGame();
                if (startGame != null) {
                    startGame.run();
                }
            });
        }
        if (lobby.isAllowNetworking() && lobby.hasControl()) {
            btnStartEvent.setFont(FSkin.getRelativeFont(18));
            btnStartEvent.addActionListener(e -> startEvent());
            btnStartMatch.setFont(FSkin.getRelativeFont(18));
            btnStartMatch.addActionListener(arg0 -> {
                Runnable startGame = lobby.startGame();
                if (startGame != null) {
                    startGame.run();
                }
            });
        }
        String defaultGamesInMatch = FModel.getPreferences().getPref(FPref.UI_MATCHES_PER_GAME);
        if (defaultGamesInMatch == null || defaultGamesInMatch.isEmpty()) {
            defaultGamesInMatch = "3";
        }

        gamesInMatchFrame.add(newLabel(localizer.getMessage("lblGamesInMatch")), "w 150px!, h 30px!");
        gamesInMatchFrame.add(gamesInMatch, "w 50px!, h 30px!");
        gamesInMatchFrame.setOpaque(false);

        pnlStart.add(gamesInMatchFrame);
    }

    public void updateDeckPanel() {
        for (final PlayerPanel playerPanel : playerPanels) {
            playerPanel.getDeckChooser().restoreSavedState();
        }
    }

    public void focusOnAvatar() {
        getPlayerPanelWithFocus().focusOnAvatar();
    }

    private PlayerPanel getPlayerPanel(int slot) {
        return playerPanels.get(slot);
    }

    @Override
    public void update(final int slot, final LobbySlotType type) {
        final FDeckChooser deckChooser = getDeckChooser(slot);
        deckChooser.setIsAi(type==LobbySlotType.AI);
        DeckType selectedDeckType = deckChooser.getSelectedDeckType();
        switch (selectedDeckType){
            case STANDARD_CARDGEN_DECK:
            case PIONEER_CARDGEN_DECK:
            case HISTORIC_CARDGEN_DECK:
            case MODERN_CARDGEN_DECK:
            case LEGACY_CARDGEN_DECK:
            case VINTAGE_CARDGEN_DECK:
            case PAUPER_CARDGEN_DECK:
            case COLOR_DECK:
            case STANDARD_COLOR_DECK:
            case MODERN_COLOR_DECK:
            case RANDOM_CARDGEN_COMMANDER_DECK:
            case RANDOM_COMMANDER_DECK:
                deckChooser.refreshDeckListForAI();
                break;
            default:
                break;
        }
    }

    @Override
    public void update(final boolean fullUpdate) {
        activePlayersNum = lobby.getNumberOfSlots();
        addPlayerBtn.setEnabled(activePlayersNum < MAX_PLAYERS);

        final boolean allowNetworking = lobby.isAllowNetworking();

        ImmutableList<VariantCheckBox> vntBoxes = null;
        if (allowNetworking) {
            vntBoxes = vntBoxesNetwork;
        } else {
            vntBoxes = vntBoxesLocal;
        }
        for (final VariantCheckBox vcb : vntBoxes) {
            vcb.setSelected(hasVariant(vcb.variant));
            vcb.setEnabled(lobby.hasControl());
        }

        for (int i = 0; i < MAX_PLAYERS; i++) {
            final boolean hasPanel = i < playerPanels.size();
            if (i < activePlayersNum) {
                // visible panels
                final LobbySlot slot = lobby.getSlot(i);
                final PlayerPanel panel;
                final boolean isNewPanel;
                if (hasPanel) {
                    panel = playerPanels.get(i);
                    isNewPanel = !panel.isVisible();
                } else {
                    panel = new PlayerPanel(this, allowNetworking, i, slot, lobby.mayEdit(i), lobby.hasControl());
                    playerPanels.add(panel);
                    String constraints = "pushx, growx, wrap, hidemode 3";
                    if (i == 0) {
                        constraints += ", gaptop 5px";
                    }
                    playersScroll.add(panel, constraints);
                    isNewPanel = true;
                }

                final LobbySlotType type = slot.getType();
                panel.setType(type);
                panel.setPlayerName(slot.getName());
                panel.setAvatarIndex(slot.getAvatarIndex());
                panel.setTeam(slot.getTeam());
                panel.setIsReady(slot.isReady());
                panel.setIsDevMode(slot.isDevMode());
                panel.setIsArchenemy(slot.isArchenemy());
                panel.setUseAiSimulation(slot.getAiOptions().contains(AIOption.USE_SIMULATION));
                panel.setMayEdit(lobby.mayEdit(i));
                panel.setMayControl(lobby.mayControl(i));
                panel.setMayRemove(lobby.mayRemove(i));
                panel.setAiProfile(slot.getAiProfile());
                panel.update();

                final boolean isSlotAI = slot.getType() == LobbySlotType.AI;
                if (isNewPanel || fullUpdate) {
                    final FDeckChooser deckChooser = createDeckChooser(lobby.getGameType(), i, isSlotAI);
                    deckChooser.populate();
                    panel.setDeckChooser(deckChooser);
                    if (i == 0) {
                        // TODO: This seems like the wrong place to do this:
                        slot.setIsDevMode(prefs.getPrefBoolean(FPref.DEV_MODE_ENABLED));
                    }
                    if (lobby.mayEdit(i)) {
                        changePlayerFocus(i);
                    }
                } else {
                    panel.getDeckChooser().setIsAi(isSlotAI);
                }
                if (fullUpdate && (type == LobbySlotType.LOCAL || isSlotAI)) {
                    // Deck section selection
                    panel.getDeckChooser().getLstDecks().getSelectCommand().run();
                    selectSchemeDeck(i);
                    selectPlanarDeck(i);
                    selectVanguardAvatar(i);
                }
                if (isNewPanel) {
                    panel.setVisible(true);
                }
            } else if (hasPanel) {
                playerPanels.get(i).setVisible(false);
            }
        }

        if (playerWithFocus >= activePlayersNum) {
            changePlayerFocus(activePlayersNum - 1);
        } else {
            populateDeckPanel(lobby.getGameType());
        }
        refreshPanels(true, true);
    }

    public void setPlayerChangeListener(final IPlayerChangeListener listener) {
        this.playerChangeListener = listener;
    }

    void setReady(final int index, final boolean ready) {
        if (ready && decks[index] == null && !vntMomirBasic.isSelected() && !vntMoJhoSto.isSelected()) {
            SOptionPane.showErrorDialog("Select a deck before readying!");
            update(false);
            return;
        }

        firePlayerChangeListener(index);
        changePlayerFocus(index);
    }
    void setDevMode(final int index) {
        // clear ready for everyone
        for (int i = 0; i < activePlayersNum; i++) {
            getPlayerPanel(i).setIsReady(false);
            firePlayerChangeListener(i);
        }
        changePlayerFocus(index);
    }
    void firePlayerChangeListener(final int index) {
        if (playerChangeListener != null) {
            playerChangeListener.update(index, getSlot(index));
        }
    }
    private void fireDeckChangeListener(final int index, final Deck deck) {
        decks[index] = deck;
        if (playerChangeListener != null) {
            playerChangeListener.update(index, UpdateLobbyPlayerEvent.deckUpdate(deck));
        }
    }
    private void fireDeckSectionChangeListener(final int index, final DeckSection section, final CardPool cards) {
        final Deck deck = decks[index];
        final Deck copy = deck == null ? new Deck() : new Deck(decks[index]);
        copy.putSection(section, cards);
        decks[index] = copy;
        if (playerChangeListener != null) {
            playerChangeListener.update(index, UpdateLobbyPlayerEvent.deckUpdate(section, cards));
        }
    }

    void removePlayer(final int index) {
        lobby.removeSlot(index);
    }
    boolean hasVariant(final GameType variant) {
        return lobby.hasVariant(variant);
    }

    private UpdateLobbyPlayerEvent getSlot(final int index) {
        final PlayerPanel panel = getPlayerPanel(index);
        return UpdateLobbyPlayerEvent.create(panel.getType(),
                panel.getPlayerName(),
                panel.getAvatarIndex(), -1 /*TODO panel.getSleeveIndex()*/,
                panel.getTeam(), panel.isArchenemy(),
                panel.isReady(),
                panel.isDevMode(),
                panel.getAiOptions(),
                panel.getAiProfile());
    }

    /** Builds the actual deck panel layouts for each player.
     * These are added to a list which can be referenced to populate the deck panel appropriately. */
    @SuppressWarnings("serial")
    private void buildDeckPanels(final int playerIndex) {
        // Scheme deck list
        buildDeckPanel(localizer.getMessage("lblSchemeDeck"), playerIndex, schemeDeckLists, schemeDeckPanels, e -> selectSchemeDeck(playerIndex));

        // Planar deck list
        buildDeckPanel(localizer.getMessage("lblPlanarDeck"), playerIndex, planarDeckLists, planarDeckPanels, e -> selectPlanarDeck(playerIndex));

        // Vanguard avatar list
        buildDeckPanel(localizer.getMessage("lblVanguardAvatar"), playerIndex, vgdAvatarLists, vgdPanels, e -> selectVanguardAvatar(playerIndex));
        Iterables.getLast(vgdAvatarLists).setListData(isPlayerAI(playerIndex) ? aiListData : humanListData);
        Iterables.getLast(vgdAvatarLists).setSelectedIndex(0);
        final CardDetailPanel vgdDetail = new CardDetailPanel();
        vgdAvatarDetails.add(vgdDetail);
        Iterables.getLast(vgdPanels).add(vgdDetail, "h 200px, pushx, growx, hidemode 3");
    }

    private void buildDeckPanel(final String formatName, final int playerIndex,
            final List<FList<Object>> deckLists, final List<FPanel> deckPanels,
            final ListSelectionListener selectionListener) {
        final FPanel deckPanel = new FPanel();
        deckPanel.setBorderToggle(false);
        deckPanel.setLayout(new MigLayout("insets 0, gap 0, wrap"));
        deckPanel.add(new FLabel.Builder().text("Select " + formatName)
                .fontStyle(Font.BOLD).fontSize(14).fontAlign(SwingConstants.CENTER)
                .build(), "gaptop 10px, gapbottom 5px, growx, pushx");
        final FList<Object> deckList = new FList<>();
        deckList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deckList.addListSelectionListener(selectionListener);

        final FScrollPane scrollPane = new FScrollPane(deckList, true,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        deckPanel.add(scrollPane, "grow, push");

        deckLists.add(deckList);
        deckPanels.add(deckPanel);
    }

    private FDeckChooser getDeckChooser(final int iSlot) {
        return getPlayerPanel(iSlot).getDeckChooser();
    }

    private void selectMainDeck(final FDeckChooser mainChooser, final int playerIndex, final boolean isCommanderDeck) {
        final DeckType type = mainChooser.getSelectedDeckType();
        final Deck deck = mainChooser.getDeck();
        // something went wrong, clear selection to prevent error loop
        if (deck == null) {
            mainChooser.getLstDecks().setSelectedIndex(0);
        }
        final Collection<DeckProxy> selectedDecks = mainChooser.getLstDecks().getSelectedItems();
        if (playerIndex < activePlayersNum && lobby.mayEdit(playerIndex)) {
            final String text = type.toString() + ": " + Lang.joinHomogenous(selectedDecks, DeckProxy::getName);
            if (isCommanderDeck) {
                getPlayerPanel(playerIndex).setCommanderDeckSelectorButtonText(text);
            } else {
                getPlayerPanel(playerIndex).setDeckSelectorButtonText(text);
            }
            fireDeckChangeListener(playerIndex, deck);
        }
        mainChooser.saveState();
    }

    private void selectSchemeDeck(final int playerIndex) {
        if (playerIndex >= activePlayersNum || !(hasVariant(GameType.Archenemy) || hasVariant(GameType.ArchenemyRumble))) {
            return;
        }

        final Object selected = getSchemeDeckLists().get(playerIndex).getSelectedValue();
        final Deck deck = decks[playerIndex];
        CardPool schemePool = null;
        if (selected instanceof String) {
            String sel = (String) selected;
            if (sel.contains("Use deck's scheme section")) {
                if (deck.has(DeckSection.Schemes)) {
                    schemePool = deck.get(DeckSection.Schemes);
                } else {
                    sel = "Random";
                }
            }
            if (sel.equals("Random")) {
                final Deck randomDeck = RandomDeckGenerator.getRandomUserDeck(lobby, isPlayerAI(playerIndex));
                schemePool = randomDeck.get(DeckSection.Schemes);
            }
        } else if (selected instanceof Deck) {
            schemePool = ((Deck) selected).get(DeckSection.Schemes);
        }
        if (schemePool == null) { //Can be null if player deselects the list selection or chose Generate
            schemePool = DeckgenUtil.generateSchemePool();
        }
        fireDeckSectionChangeListener(playerIndex, DeckSection.Schemes, schemePool);
        getDeckChooser(playerIndex).saveState();
    }

    private void selectPlanarDeck(final int playerIndex) {
        if (playerIndex >= activePlayersNum || !hasVariant(GameType.Planechase)) {
            return;
        }

        final Object selected = getPlanarDeckLists().get(playerIndex).getSelectedValue();
        final Deck deck = decks[playerIndex];
        CardPool planePool = null;
        if (selected instanceof String) {
            String sel = (String) selected;
            if (sel.contains("Use deck's planes section")) {
                if (deck.has(DeckSection.Planes)) {
                    planePool = deck.get(DeckSection.Planes);
                } else {
                    sel = "Random";
                }
            }
            if (sel.equals("Random")) {
                final Deck randomDeck = RandomDeckGenerator.getRandomUserDeck(lobby, isPlayerAI(playerIndex));
                planePool = randomDeck.get(DeckSection.Planes);
            }
        } else if (selected instanceof Deck) {
            planePool = ((Deck) selected).get(DeckSection.Planes);
        }
        if (planePool == null) { //Can be null if player deselects the list selection or chose Generate
            planePool = DeckgenUtil.generatePlanarPool();
        }
        fireDeckSectionChangeListener(playerIndex, DeckSection.Planes, planePool);
        getDeckChooser(playerIndex).saveState();
    }

    private void selectVanguardAvatar(final int playerIndex) {
        if (playerIndex >= activePlayersNum || !hasVariant(GameType.Vanguard)) {
            return;
        }

        final Object selected = vgdAvatarLists.get(playerIndex).getSelectedValue();
        final PlayerPanel pp = getPlayerPanel(playerIndex);
        final CardDetailPanel cdp = vgdAvatarDetails.get(playerIndex);

        PaperCard vanguardAvatar = null;
        final Deck deck = decks[playerIndex];
        if (selected instanceof PaperCard) {
            pp.setVanguardButtonText(((PaperCard) selected).getDisplayName());
            cdp.setCard(CardView.getCardForUi((PaperCard) selected));
            cdp.setVisible(true);
            refreshPanels(false, true);

            vanguardAvatar = (PaperCard)selected;
        } else {
            final String sel = (String) selected;
            pp.setVanguardButtonText(sel);
            cdp.setVisible(false);

            if (sel == null) {
                return;
            }
            if (sel.contains("Use deck's default avatar") && deck != null && deck.has(DeckSection.Avatar)) {
                vanguardAvatar = deck.get(DeckSection.Avatar).get(0);
            } else { //Only other string is "Random"
                if (isPlayerAI(playerIndex)) { //AI
                    vanguardAvatar = Aggregates.random(getNonRandomAiAvatars());
                } else { //Human
                    vanguardAvatar = Aggregates.random(getNonRandomHumanAvatars());
                }
            }
        }

        final CardPool avatarOnce = new CardPool();
        avatarOnce.add(vanguardAvatar);
        fireDeckSectionChangeListener(playerIndex, DeckSection.Avatar, avatarOnce);
        getDeckChooser(playerIndex).saveState();
    }

    /** Populates the deck panel with the focused player's deck choices. */
    private void populateDeckPanel(final GameType forGameType) {
        decksFrame.removeAll();

        if (!lobby.mayEdit(playerWithFocus)) {
            return;
        }

        switch (forGameType) {
        case Constructed:
            decksFrame.add(getDeckChooser(playerWithFocus), "grow, push");
            if (getDeckChooser(playerWithFocus).getSelectedDeckType().toString().contains(localizer.getMessage("lblRandom"))) {
                final String strCheckboxConstraints = "h 30px!, gap 0 20px 0 0";
                decksFrame.add(cbSingletons, strCheckboxConstraints);
                decksFrame.add(cbArtifacts, strCheckboxConstraints);
            }
            break;
        case Archenemy:
        case ArchenemyRumble:
            if (isPlayerArchenemy(playerWithFocus)) {
                decksFrame.add(schemeDeckPanels.get(playerWithFocus), "grow, push");
            } else {
                populateDeckPanel(GameType.Constructed);
            }
            break;
        case Commander:
        case Oathbreaker:
        case TinyLeaders:
        case Brawl:
            decksFrame.add(getDeckChooser(playerWithFocus), "grow, push");
            break;
        case Planechase:
            decksFrame.add(planarDeckPanels.get(playerWithFocus), "grow, push");
            break;
        case Vanguard:
            updateVanguardList(playerWithFocus);
            decksFrame.add(vgdPanels.get(playerWithFocus), "grow, push");
            break;
        default:
            break;
        }
        refreshPanels(false, true);
    }

    /** @return {@link javax.swing.JButton} */
    JButton getBtnStart() {
        return this.btnStart;
    }

    public LblHeader getLblTitle() { return lblTitle; }
    public JPanel getConstructedFrame() { return constructedFrame; }
    public JPanel getPanelStart() { return pnlStart; }
    public List<FDeckChooser> getDeckChoosers() {
        List<FDeckChooser> choosers = Lists.newArrayList();
        for (final PlayerPanel playerPanel : playerPanels) {
            choosers.add(playerPanel.getDeckChooser());
        }
        return choosers;
    }

    /** Gets the random deck checkbox for Singletons. */
    FCheckBox getCbSingletons() { return cbSingletons; }

    /** Gets the random deck checkbox for Artifacts. */
    FCheckBox getCbArtifacts() { return cbArtifacts; }

    public final List<PlayerPanel> getPlayerPanels() {
        return playerPanels;
    }
    private PlayerPanel getPlayerPanelWithFocus() {
        return getPlayerPanels().get(playerWithFocus);
    }
    boolean hasFocus(final int iPlayer) {
        return iPlayer == playerWithFocus;
    }

    void setCurrentGameMode(final GameType mode) {
        lobby.setGameType(mode);
        update(true);
    }

    private boolean isPlayerAI(final int playernum) {
        if (playernum < activePlayersNum) {
            return playerPanels.get(playernum).isAi();
        }
        return true;
    }

    /** Revalidates the player and deck sections. Necessary after adding or hiding any panels. */
    private void refreshPanels(final boolean refreshPlayerFrame, final boolean refreshDeckFrame) {
        if (refreshPlayerFrame) {
            playersScroll.validate();
            playersScroll.repaint();
        }
        if (refreshDeckFrame) {
            decksFrame.validate();
            decksFrame.repaint();
        }
    }

    public void changePlayerFocus(final int newFocusOwner) {
        changePlayerFocus(newFocusOwner, lobby.getGameType());
    }

    void changePlayerFocus(final int newFocusOwner, final GameType gType) {
        final PlayerPanel oldFocus = getPlayerPanelWithFocus();
        if (oldFocus != null) {
            oldFocus.setFocused(false);
        }
        playerWithFocus = newFocusOwner;
        final PlayerPanel newFocus = getPlayerPanelWithFocus();
        newFocus.setFocused(true);

        playersScroll.getViewport().scrollRectToVisible(newFocus.getBounds());
        populateDeckPanel(gType);

        refreshPanels(true, true);
    }

    /////////////////////////////////////////////
    //========== Mode selector methods (network Draft/Sealed)

    public LobbyMode getCurrentMode() {
        return currentMode;
    }

    private void onModeChanged() {
        final String selected = cboModePanel.getSelectedItem();
        if ("Draft".equals(selected)) {
            currentMode = LobbyMode.DRAFT;
        } else if ("Sealed".equals(selected)) {
            currentMode = LobbyMode.SEALED;
        } else {
            currentMode = LobbyMode.CONSTRUCTED;
        }

        final boolean isLimited = (currentMode != LobbyMode.CONSTRUCTED);

        // Toggle variants panel visibility — it's inside an FScrollPane
        java.awt.Container scrollPane = variantsPanel.getParent();
        while (scrollPane != null && !(scrollPane instanceof JScrollPane)) {
            scrollPane = scrollPane.getParent();
        }
        if (scrollPane != null) {
            scrollPane.setVisible(!isLimited);
        }

        // Update right panel content
        updateRightPanelForMode();

        // Update action buttons
        updateActionButtons();

        constructedFrame.revalidate();
        constructedFrame.repaint();
    }

    private void updateRightPanelForMode() {
        decksFrame.removeAll();
        if (currentMode == LobbyMode.CONSTRUCTED) {
            // Restore standard constructed deck chooser
            populateDeckPanel(lobby.getGameType());
        } else {
            // Draft/Sealed mode: tabbed panel with Event Config (default) and Deck Select
            rightTabs.removeAll();
            rightTabs.addTab("Event Configuration", eventConfigPanel);
            if (playerWithFocus < playerPanels.size() && lobby.mayEdit(playerWithFocus)) {
                final FDeckChooser chooser = getDeckChooser(playerWithFocus);
                if (chooser != null) {
                    rightTabs.addTab("Deck Select", chooser);
                }
            }
            rightTabs.setSelectedIndex(0); // Event Config is the default tab
            decksFrame.add(rightTabs, "w 100%, h 100%, growy, pushy");
        }
        decksFrame.revalidate();
        decksFrame.repaint();
    }

    private void updateActionButtons() {
        final boolean isLimited = (currentMode != LobbyMode.CONSTRUCTED);

        // Rebuild pnlStart layout
        pnlStart.removeAll();
        pnlStart.setOpaque(false);
        if (lobby.hasControl()) {
            if (isLimited) {
                // In Draft/Sealed mode: event button, Start Match, games-in-match in a single row
                pnlStart.setLayout(new MigLayout("insets 0, gap 0"));
                final String label = (currentMode == LobbyMode.DRAFT) ? "Start Draft" : "Generate Pools";
                btnStartEvent.setText(label);
                pnlStart.add(btnStartEvent, "w 200px!, h 50px!, gapright 40");
                pnlStart.add(btnStartMatch, "w 200px!, h 50px!, gapright 10");
                pnlStart.add(gamesInMatchFrame);
            } else {
                // Constructed mode: Start button centered with games-in-match below
                pnlStart.setLayout(new MigLayout("insets 0, gap 0, wrap 2"));
                pnlStart.add(btnStart, "align center, spanx 2, wrap");
                pnlStart.add(gamesInMatchFrame, "spanx 2, align center");
            }
        } else {
            pnlStart.add(gamesInMatchFrame, "spanx 2, align center");
        }
        pnlStart.revalidate();
        pnlStart.repaint();
    }

    private void startEvent() {
        if (currentMode == LobbyMode.SEALED) {
            // Will wire to ServerGameLobby.generateAndDistributeSealedPools()
        } else if (currentMode == LobbyMode.DRAFT) {
            if (!(lobby instanceof forge.gamemodes.net.server.ServerGameLobby serverLobby)) {
                return;
            }
            forge.gamemodes.net.draft.NetworkEvent event = serverLobby.getCurrentEvent();
            if (event == null) {
                FOptionPane.showErrorDialog("No event configured. Use Draft mode and wait for players.");
                return;
            }

            // Build participant info from event
            java.util.List<forge.gamemodes.net.draft.EventParticipant> participants = event.getParticipants();
            int podSize = participants.size();
            if (podSize < 2) {
                FOptionPane.showErrorDialog("Need at least 2 participants to start a draft.");
                return;
            }

            // Create a draft with deferred initialization so we can configure seats first
            forge.gamemodes.limited.BoosterDraft draft =
                    forge.gamemodes.limited.BoosterDraft.createDraftForNetwork(
                            forge.gamemodes.limited.LimitedPoolType.Full);
            if (draft == null) {
                FOptionPane.showErrorDialog("Failed to create draft.");
                return;
            }

            // Configure pod size and mark human seats before distributing boosters
            if (podSize != draft.getPodSize()) {
                draft.setPodSize(podSize);
            }
            java.util.Set<Integer> humanSeats = new java.util.HashSet<>();
            for (forge.gamemodes.net.draft.EventParticipant p : participants) {
                if (p.isHuman()) {
                    humanSeats.add(p.getSeatIndex());
                }
            }
            draft.setHumanSeats(humanSeats);
            draft.initializeBoosters();

            // Determine host seat index
            String hostName = forge.model.FModel.getPreferences().getPref(
                    forge.localinstance.properties.ForgePreferences.FPref.PLAYER_NAME);
            int mySeat = 0;
            String[] names = new String[podSize];
            boolean[] aiFlags = new boolean[podSize];
            for (forge.gamemodes.net.draft.EventParticipant p : participants) {
                int seat = p.getSeatIndex();
                if (seat >= 0 && seat < podSize) {
                    names[seat] = p.getName();
                    aiFlags[seat] = p.isAI();
                    if (p.isHuman() && p.getName().equals(hostName)) {
                        mySeat = seat;
                    }
                }
            }

            int totalPacks = draft.getNumRounds();
            forge.gui.FDraftOverlay.SINGLETON_INSTANCE.initDraft(mySeat, names, aiFlags, totalPacks);

            // Start the draft — this sends packs to humans (including host via lobbyListener)
            serverLobby.startDraft(draft);
        }
    }

    private void openEventConfigDialog() {
        // TODO: Wire to existing booster/product configuration dialog
        // For now, show a placeholder message
        FOptionPane.showMessageDialog("Event configuration dialog will be implemented in a later stage.");
    }

    /** Saves avatar prefs for players one and two. */
    void updateAvatarPrefs() {
        final int pOneIndex = getPlayerPanel(0).getAvatarIndex();
        final int pTwoIndex = getPlayerPanel(1).getAvatarIndex();

        prefs.setPref(FPref.UI_AVATARS, pOneIndex + "," + pTwoIndex);
        prefs.save();
    }

    /** Saves sleeve prefs for players one and two. */
    void updateSleevePrefs() {
        final int pOneIndex = getPlayerPanel(0).getSleeveIndex();
        final int pTwoIndex = getPlayerPanel(1).getSleeveIndex();

        prefs.setPref(FPref.UI_SLEEVES, pOneIndex + "," + pTwoIndex);
        prefs.save();
    }

    /** Adds a pre-styled FLabel component with the specified title. */
    FLabel newLabel(final String title) {
        return new FLabel.Builder().text(title).fontSize(14).fontStyle(Font.ITALIC).build();
    }

    List<Integer> getUsedAvatars() {
        final List<Integer> usedAvatars = Lists.newArrayListWithCapacity(MAX_PLAYERS);
        for (final PlayerPanel pp : playerPanels) {
            usedAvatars.add(pp.getAvatarIndex());
        }
        return usedAvatars;
    }

    List<Integer> getUsedSleeves() {
        final List<Integer> usedSleeves = Lists.newArrayListWithCapacity(MAX_PLAYERS);
        for (final PlayerPanel pp : playerPanels) {
            usedSleeves.add(pp.getSleeveIndex());
        }
        return usedSleeves;
    }

    private static final ImmutableList<String> genderOptions = ImmutableList.of("Male",    "Female",  "Any"),
                                               typeOptions   = ImmutableList.of("Fantasy", "Generic", "Any");
    final String getNewName() {
        final String title = localizer.getMessage("lblGetNewRandomName");
        final String message = localizer.getMessage("lbltypeofName");
        final SkinImage icon = FOptionPane.QUESTION_ICON;

        final int genderIndex = FOptionPane.showOptionDialog(message, title, icon, genderOptions, 2);
        if (genderIndex < 0) {
            return null;
        }
        final int typeIndex = FOptionPane.showOptionDialog(message, title, icon, typeOptions, 2);
        if (typeIndex < 0) {
            return null;
        }

        final String gender = genderOptions.get(genderIndex);
        final String type = typeOptions.get(typeIndex);

        String confirmMsg, newName;
        final List<String> usedNames = getPlayerNames();
        do {
            newName = NameGenerator.getRandomName(gender, type, usedNames);
            confirmMsg = localizer.getMessage("lblconfirmName").replace("%s","\"" +newName + "\"");
        } while (!FOptionPane.showConfirmDialog(confirmMsg, title, localizer.getMessage("lblUseThisName"), localizer.getMessage("lblTryAgain"), true));

        return newName;
    }

    List<String> getPlayerNames() {
        final List<String> names = new ArrayList<>();
        for (final PlayerPanel pp : playerPanels) {
            names.add(pp.getPlayerName());
        }
        return names;
    }

    /////////////////////////////////////////////
    //========== Various listeners in build order

    @SuppressWarnings("serial") private class VariantCheckBox extends FCheckBox {
        private final GameType variant;
        private VariantCheckBox(final GameType variantType) {
            super(variantType.toString());
            this.variant = variantType;

            setToolTipText(variantType.getDescription());
            addItemListener(e -> {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    lobby.applyVariant(variantType);
                } else {
                    lobby.removeVariant(variantType);
                }
                VLobby.this.update(false);
            });
        }
    }

    private FDeckChooser createDeckChooser(final GameType type, final int iSlot, final boolean ai) {
        boolean forCommander;
        DeckType deckType;
        FPref prefKey;
        switch (type) {
            case Commander:
                forCommander = true;
                deckType = iSlot == 0 ? DeckType.COMMANDER_DECK : DeckType.RANDOM_CARDGEN_COMMANDER_DECK;
                prefKey = FPref.COMMANDER_DECK_STATES[iSlot];
                break;
            case TinyLeaders:
                forCommander = true;
                deckType = iSlot == 0 ? DeckType.TINY_LEADERS_DECK : DeckType.RANDOM_CARDGEN_COMMANDER_DECK;
                prefKey = FPref.TINY_LEADER_DECK_STATES[iSlot];
                break;
            case Oathbreaker:
                forCommander = true;
                deckType = iSlot == 0 ? DeckType.OATHBREAKER_DECK : DeckType.RANDOM_CARDGEN_COMMANDER_DECK;
                prefKey = FPref.OATHBREAKER_DECK_STATES[iSlot];
                break;
            case Brawl:
                forCommander = true;
                deckType = iSlot == 0 ? DeckType.BRAWL_DECK : DeckType.CUSTOM_DECK;
                prefKey = FPref.BRAWL_DECK_STATES[iSlot];
                break;
            default:
                forCommander = false;
                deckType = iSlot == 0 ? DeckType.PRECONSTRUCTED_DECK : DeckType.COLOR_DECK;
                prefKey = FPref.CONSTRUCTED_DECK_STATES[iSlot];
                break;
        }
        return cachedDeckChoosers.computeIfAbsent(prefKey, (key) -> {
            final GameType gameType = forCommander ? type : GameType.Constructed;
            final FDeckChooser fdc = new FDeckChooser(null, ai, gameType, forCommander);
            fdc.initialize(prefKey, deckType);
            fdc.getLstDecks().setSelectCommand(() -> selectMainDeck(fdc, iSlot, forCommander));
            return fdc;
        });
    }

    final ActionListener nameListener = e -> {
        final FTextField nField = (FTextField)e.getSource();
        nField.transferFocus();
    };

    /////////////////////////////////////
    //========== METHODS FOR VARIANTS

    /** Gets the list of planar deck lists. */
    public List<FList<Object>> getPlanarDeckLists() {
        return planarDeckLists;
    }

    /** Gets the list of scheme deck lists. */
    public List<FList<Object>> getSchemeDeckLists() {
        return schemeDeckLists;
    }

    public boolean isPlayerArchenemy(final int playernum) {
        return getPlayerPanel(playernum).isArchenemy();
    }

    /** Gets the list of Vanguard avatar lists. */
    public List<FList<Object>> getVanguardLists() {
        return vgdAvatarLists;
    }

    /** Return all the Vanguard avatars. */
    public Iterable<PaperCard> getAllAvatars() {
        if (vgdAllAvatars.isEmpty()) {
            for (final PaperCard c : FModel.getMagicDb().getVariantCards().getAllCards()) {
                if (c.getRules().getType().isVanguard()) {
                    vgdAllAvatars.add(c);
                }
            }
        }
        return vgdAllAvatars;
    }

    /** Return the Vanguard avatars not flagged RemoveDeck:Random. */
    public List<PaperCard> getNonRandomHumanAvatars() {
        return nonRandomHumanAvatars;
    }

    /** Return the Vanguard avatars not flagged RemoveDeck:All or RemoveDeck:Random. */
    public List<PaperCard> getNonRandomAiAvatars() {
        return nonRandomAiAvatars;
    }

    /** Return the gamesInMatchBinder */
    public SwingPrefBinders.ComboBox getGamesInMatchBinder() {
      return gamesInMatchBinder;
    }

    /** Populate vanguard lists. */
    private void populateVanguardLists() {
        humanListData.add("Use deck's default avatar (random if unavailable)");
        humanListData.add("Random");
        aiListData.add("Use deck's default avatar (random if unavailable)");
        aiListData.add("Random");
        for (final PaperCard cp : getAllAvatars()) {
            humanListData.add(cp);
            if (!cp.getRules().getAiHints().getRemRandomDecks()) {
                nonRandomHumanAvatars.add(cp);
            }
            if (!cp.getRules().getAiHints().getRemAIDecks()) {
                aiListData.add(cp);
                if (!cp.getRules().getAiHints().getRemRandomDecks()) {
                    nonRandomAiAvatars.add(cp);
                }
            }
        }
    }

    /** update vanguard list. */
    public void updateVanguardList(final int playerIndex) {
        final FList<Object> vgdList = getVanguardLists().get(playerIndex);
        final Object lastSelection = vgdList.getSelectedValue();
        vgdList.setListData(isPlayerAI(playerIndex) ? aiListData : humanListData);
        if (null != lastSelection) {
            vgdList.setSelectedValue(lastSelection, true);
        }

        if (-1 == vgdList.getSelectedIndex()) {
            vgdList.setSelectedIndex(0);
        }
    }

    /////////////////////////////////////////////
    //========== ILobbyView draft callbacks (network draft/sealed)

    @Override
    public void onDraftPackArrived(int seatIndex, java.util.List<PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds) {
        forge.gui.FDraftOverlay.SINGLETON_INSTANCE.onPackArrived(packNumber, timerDurationSeconds);
    }

    @Override
    public void onDraftSeatPicked(int seatIndex, int pickNumber, int[] seatQueueDepths) {
        forge.gui.FDraftOverlay.SINGLETON_INSTANCE.onSeatPicked(seatIndex, seatQueueDepths);
    }

    @Override
    public void onReceiveEventPool(String eventId, forge.deck.DeckGroup pool) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            // Save the drafted pool to the network event decks storage
            FModel.getDecks().getNetworkEventDecks().add(pool);
            forge.gui.FDraftOverlay.SINGLETON_INSTANCE.reset();
            FOptionPane.showMessageDialog("Draft complete! Your pool has been saved as '"
                    + pool.getName() + "'.");
        });
    }
}
