package forge.web;

import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.gson.JsonObject;
import forge.card.CardEdition;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.NewQuestRules;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestDraftUtils;
import forge.gamemodes.quest.QuestEvent;
import forge.gamemodes.quest.QuestEventChallenge;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.QuestEventDraft.QuestDraftFormat;
import forge.gamemodes.quest.QuestEventDraft.QuestDraftPrizes;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestSpellShop;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.QuestWinLoseController;
import forge.gamemodes.quest.StartingPoolPreferences.PoolType;
import forge.gamemodes.quest.StartingPoolPreferences;
import forge.gamemodes.quest.StartingPoolType;
import forge.gamemodes.quest.bazaar.IQuestBazaarItem;
import forge.gamemodes.quest.bazaar.QuestBazaarManager;
import forge.gamemodes.quest.bazaar.QuestItemBasic;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.bazaar.QuestPetController;
import forge.gamemodes.quest.bazaar.QuestStallDefinition;
import forge.gamemodes.quest.data.DeckConstructionRules;
import forge.gamemodes.quest.data.QuestAchievements;
import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.DifficultyPrefs;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.gui.GuiBase;
import forge.gui.util.SGuiChoose;
import forge.gui.util.SOptionPane;
import forge.item.BoosterPack;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.itemmanager.SItemManagerUtil;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.ItemPool;
import forge.util.Localizer;
import forge.util.storage.IStorage;
import forge.web.FromBrowser.CatalogueQuery;
import forge.web.FromBrowser.TradePick;
import forge.web.ToBrowser.*;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/** Holds only what one session knows that the save does not, as the quest itself is Forge's, one for the whole process. */
final class QuestGame implements Campaign {
    private static final String[] DIFFICULTIES = {"questDifficultyEasy", "questDifficultyMedium", "questDifficultyHard", "questDifficultyExpert"};
    private List<QuestEventDuel> duels;
    /** The match being played: its view keeps every game's steps, and the latest game's controller records the match. */
    private volatile WebQuestView view;
    private volatile QuestWinLoseController controller;
    /** The result of the game just ended, while its match is still open. */
    private volatile CampaignResult result;
    /** What the last match gave, until the browser says it has shown it. */
    private volatile Reward reward;
    /** The computer the player's tournament match is against, while one is played. */
    private volatile String tournamentOpponent;
    /** Whether that match has been decided, so leaving it is no forfeit. */
    private volatile boolean tournamentDecided;

    static QuestController quest() {
        return FModel.getQuest();
    }

    @Override
    public QuestSaves saves() {
        final List<QuestSave> rows = new ArrayList<>();
        final File[] files = new File(ForgeConstants.QUEST_SAVE_DIR).listFiles((dir, name) -> name.endsWith(".dat"));
        for (final File file : files == null ? new File[0] : files) {
            final QuestData data = read(file);
            if (data != null) {
                rows.add(row(data, file));
            }
        }
        rows.sort(Comparator.comparing(QuestSave::name, String.CASE_INSENSITIVE_ORDER));
        return new QuestSaves(rows, current());
    }

    private static QuestData read(final File file) {
        try {
            return QuestDataIO.loadData(file);
        } catch (final IOException | RuntimeException e) {
            Logger.warn("Could not read the quest {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static QuestSave row(final QuestData data, final File file) {
        final int difficulty = data.getAchievements().getDifficulty();
        return new QuestSave(data.getName(), data.getMode().toString(),
                difficulty >= 0 && difficulty < DIFFICULTIES.length ? Localizer.getInstance().getMessage(DIFFICULTIES[difficulty]) : "",
                quest().getRank(data.getAchievements().getLevel()), data.getAchievements().getWin(), data.getAchievements().getLost(),
                data.getAssets().getCardPool().countAll(), data.getAssets().getCredits(), world(data.getWorldId()),
                LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).toString());
    }

    private static String world(final String id) {
        return id == null ? Localizer.getInstance().getMessage("lblNone") : id;
    }

    /** The quest played last, if its file is still there. */
    @Override
    public String current() {
        final String file = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        if (file == null || !file.endsWith(".dat")) {
            return null;
        }
        final String name = file.substring(0, file.length() - ".dat".length());
        return find(name) != null ? name : null;
    }

    @Override
    public String open(final String save) {
        final File file = find(save);
        final QuestData data = file == null ? null : read(file);
        if (data == null) {
            return null;
        }
        quest().load(data);
        duels = null;
        // A reward not yet shown is already in its save, and belongs to the quest that was open
        reward = null;
        // As desktop's quest chooser does, so desktop and mobile open the same quest next
        final QuestPreferences prefs = FModel.getQuestPreferences();
        prefs.setPref(QPref.CURRENT_QUEST, file.getName());
        prefs.save();
        return data.getName();
    }

    /** The save file of a name, or null when there is none. */
    static File find(final String name) {
        if (name == null) {
            return null;
        }
        final File file = new File(ForgeConstants.QUEST_SAVE_DIR, name + ".dat");
        // The name is the browser's: one with a path in it would reach a file that is not a save
        try {
            final File saves = new File(ForgeConstants.QUEST_SAVE_DIR).getCanonicalFile();
            final File canonical = file.getCanonicalFile();
            return file.isFile() && saves.equals(canonical.getParentFile()) && canonical.getName().equals(name + ".dat") ? file : null;
        } catch (final IOException e) {
            return null;
        }
    }

    /** Renames a save and its backup. The quest Forge has open is read again under its new name, or its next save would bring the old file back. */
    @Override
    public synchronized String rename(final String name, final String to) {
        final File file = find(name);
        final String clean = NewQuestRules.cleanName(to);
        if (file == null) {
            return null;
        }
        if (clean.isEmpty()) {
            return Localizer.getInstance().getMessage("lblQuestNameEmpty");
        }
        if (new File(ForgeConstants.QUEST_SAVE_DIR, clean + ".dat").exists()) {
            return Localizer.getInstance().getMessage("lblQuestExists");
        }
        final QuestData data = read(file);
        if (data == null) {
            return null;
        }
        final boolean open = name.equals(quest().getName());
        data.rename(clean);
        if (open) {
            quest().load(data);
        }
        // Desktop and mobile leave the preference naming a file that is gone
        final QuestPreferences prefs = FModel.getQuestPreferences();
        if (file.getName().equals(prefs.getPref(QPref.CURRENT_QUEST))) {
            prefs.setPref(QPref.CURRENT_QUEST, clean + ".dat");
            prefs.save();
        }
        return null;
    }

    @Override
    public synchronized void delete(final String name) {
        final File file = find(name);
        if (file == null) {
            return;
        }
        if (name.equals(quest().getName())) {
            quest().load(null);
        }
        file.delete();
        new File(file.getPath() + ".bak").delete();
        final QuestPreferences prefs = FModel.getQuestPreferences();
        if (file.getName().equals(prefs.getPref(QPref.CURRENT_QUEST))) {
            prefs.setPref(QPref.CURRENT_QUEST, QPref.CURRENT_QUEST.getDefault());
            prefs.save();
        }
    }

    /** What the new-quest form offers. */
    private static QuestOptions options() {
        final Localizer text = Localizer.getInstance();
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final List<String> worlds = new ArrayList<>();
        FModel.getWorlds().forEach(w -> worlds.add(w.getName()));
        final List<String> sanctioned = new ArrayList<>();
        FModel.getFormats().getSanctionedList().forEach(f -> sanctioned.add(f.getName()));
        final List<String> sealed = new ArrayList<>();
        FModel.getDecks().getSealed().forEach(d -> sealed.add(d.getName()));
        final List<String> draft = new ArrayList<>();
        FModel.getDecks().getDraft().forEach(d -> draft.add(d.getName()));
        final List<String> cubes = new ArrayList<>();
        FModel.getDecks().getCubes().forEach(d -> cubes.add(d.getName()));
        final List<QuestDifficultyRow> difficulties = new ArrayList<>();
        for (int i = 0; i < DIFFICULTIES.length; i++) {
            difficulties.add(new QuestDifficultyRow(text.getMessage(DIFFICULTIES[i]), prefs.getPrefInt(DifficultyPrefs.STARTING_CREDITS, i),
                    prefs.getPrefInt(DifficultyPrefs.STARTING_COMMONS, i), prefs.getPrefInt(DifficultyPrefs.STARTING_UNCOMMONS, i),
                    prefs.getPrefInt(DifficultyPrefs.STARTING_RARES, i)));
        }
        return new QuestOptions(worlds, NewQuestRules.formatWorlds(), sanctioned, NewQuestRules.casualFormats(), NewQuestRules.startingPrecons(),
                sealed, draft, cubes, difficulties);
    }

    /** The form's answers as the shared rules take them; null for a pool or prize type the form does not offer. */
    private static NewQuestRules.Choices choices(final FromBrowser.QuestCreate c) {
        final StartingPoolType pool = poolType(c.pool());
        if (pool == null || pool == StartingPoolType.CustomFormat) {
            return null;
        }
        final List<Byte> colours = new ArrayList<>();
        final String letters = c.colors() == null ? "" : c.colors();
        for (int i = 0; i < MagicColor.WUBRGC.length; i++) {
            if (letters.indexOf("WUBRGC".charAt(i)) >= 0) {
                colours.add(MagicColor.WUBRGC[i]);
            }
        }
        PoolType distribution;
        try {
            distribution = PoolType.valueOf(c.poolType());
        } catch (final IllegalArgumentException | NullPointerException e) {
            distribution = PoolType.BALANCED;
        }
        return new NewQuestRules.Choices(c.name(), Math.max(0, Math.min(DIFFICULTIES.length - 1, c.difficulty())),
                c.fantasy() ? QuestMode.Fantasy : QuestMode.Classic, c.commander(), c.world(), pool, c.format(), c.precon(), c.savedDeck(),
                new StartingPoolPreferences(distribution, colours, c.artifacts(), c.completeSet(), c.duplicates(), Math.max(0, c.boosters())),
                poolType(c.prizes()), c.prizeFormat(), c.allowUnlocks());
    }

    private static StartingPoolType poolType(final String name) {
        try {
            return name == null ? null : StartingPoolType.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** The shelf's own messages: the form's options, and a new quest from them. */
    private void shelf(final BrowserChannel channel, final JsonObject msg, final Host host) {
        switch (msg.get("t").getAsString()) {
            case "questOptions" -> channel.send(options());
            case "questCreate" -> {
                final NewQuestRules.Choices choices = choices(Wire.decode(msg, FromBrowser.QuestCreate.class));
                final String problem = choices == null ? Localizer.getInstance().getMessage("lbldckStartPool") : NewQuestRules.problem(choices);
                if (problem != null) {
                    channel.send(host.error(problem));
                    return;
                }
                synchronized (this) {
                    NewQuestRules.start(choices);
                }
                duels = null;
                reward = null;
                host.opened(quest().getName());
            }
            default -> { }
        }
    }

    @Override
    public List<Record> page() {
        return List.of(bar(), duelsPage(), decksPage(), challengesPage(), tournamentsPage());
    }

    /** The quest's decks, by name. */
    private QuestDecks decksPage() {
        final String current = quest().getCurrentDeck();
        final List<QuestDeckRow> rows = new ArrayList<>();
        for (final Deck deck : quest().getMyDecks()) {
            final CardPool side = deck.get(DeckSection.Sideboard);
            rows.add(new QuestDeckRow(deck.getName(), face(deck), DeckCatalog.colors(deck), deck.getMain().countAll(), side == null ? 0 : side.countAll(),
                    QuestUtil.getDeckConformanceProblemsBeforeGame(deck), deck.getName().equals(current)));
        }
        rows.sort(Comparator.comparing(QuestDeckRow::name, String.CASE_INSENSITIVE_ORDER));
        return new QuestDecks(rows);
    }

    /** What the deck editor builds a quest deck from: the quest's cards, every printing counted, with a sideboard of its own. */
    private static DeckEditor.Collection collection() {
        return new DeckEditor.Collection(quest().getName(), () -> {
            final CardPool owned = new CardPool();
            owned.addAll(quest().getCards().getCardpool());
            return owned;
        }, card -> quest().getCards().isNew(card), deck -> quest().getAvailableLandSets(), () -> quest().save(), false);
    }

    /** A Commander quest's decks are checked as Commander decks, every other quest's as Quest's. */
    private static GameType deckType() {
        return quest().getDeckConstructionRules() == DeckConstructionRules.Commander ? GameType.Commander : GameType.Quest;
    }

    /** Why a quest deck cannot take a name, or null when it can. */
    private static String deckNameProblem(final String name) {
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            return problem;
        }
        return quest().getMyDecks().contains(name.trim()) ? Localizer.getInstance().getMessage("lblWebEditorNameTaken", name.trim()) : null;
    }

    /** A deck's own commands, from the Decks page. Answers false for a message that is not one. */
    private boolean deckCommand(final BrowserChannel channel, final JsonObject msg, final Host host) {
        final IStorage<Deck> decks = quest().getMyDecks();
        switch (msg.get("t").getAsString()) {
            case "questDeckNew" -> {
                final String name = Wire.decode(msg, FromBrowser.QuestDeckNew.class).name();
                final String problem = name == null ? DeckStore.nameProblem("") : deckNameProblem(name);
                if (problem != null) {
                    channel.send(host.error(problem));
                    return true;
                }
                // Stored at once, so the deck is on the Decks page whether or not a card is added
                final Deck deck = new Deck(name.trim());
                decks.add(deck);
                quest().save();
                host.decks().openCollectionDeck(deck, decks, deckType(), collection(), true, channel);
            }
            case "questDeckRename" -> {
                final FromBrowser.QuestDeckRename rename = Wire.decode(msg, FromBrowser.QuestDeckRename.class);
                final Deck deck = rename.deck() == null ? null : decks.get(rename.deck());
                final String problem = deck == null || rename.to() == null ? DeckStore.nameProblem("") : deckNameProblem(rename.to());
                if (problem != null) {
                    channel.send(host.error(problem));
                    return true;
                }
                final boolean wasCurrent = deck.getName().equals(quest().getCurrentDeck());
                decks.delete(deck.getName());
                deck.setName(rename.to().trim());
                decks.add(deck);
                if (wasCurrent) {
                    quest().setCurrentDeck(deck.getName());
                }
                quest().save();
            }
            case "questDeckCurrent", "questDeckView", "questDeckEdit", "questDeckDelete" -> {
                final FromBrowser.QuestDeckCommand command = Wire.decode(msg, FromBrowser.QuestDeckCommand.class);
                final Deck deck = command.deck() == null ? null : decks.get(command.deck());
                if (deck == null) {
                    return true;
                }
                switch (command.t()) {
                    case questDeckCurrent -> quest().setCurrentDeck(deck.getName());
                    case questDeckView -> {
                        channel.send(new DeckDetailsMessage(new DeckDetails("quest:" + deck.getName(), deck.getName(),
                                QuestUtil.getDeckConformanceProblemsBeforeGame(deck), DeckCatalog.colors(deck), DeckCatalog.stats(deck),
                                DeckEditor.groups(deck.getMain(), DeckCatalog.NO_FLAGS), DeckEditor.cards(deck.get(DeckSection.Sideboard), DeckCatalog.NO_FLAGS),
                                null, 0, null)));
                        return true;
                    }
                    case questDeckEdit -> {
                        host.decks().openCollectionDeck(deck, decks, deckType(), collection(), true, channel);
                        return true;
                    }
                    case questDeckDelete -> {
                        decks.delete(deck.getName());
                        if (deck.getName().equals(quest().getCurrentDeck())) {
                            quest().setCurrentDeck(QPref.CURRENT_DECK.getDefault());
                        }
                    }
                }
                quest().save();
            }
            default -> {
                return false;
            }
        }
        // The Duels page names the deck duels use
        channel.send(duelsPage());
        channel.send(decksPage());
        return true;
    }

    /** The duels on offer, kept so that an index the browser sends back names the duel it was shown. */
    private List<QuestEventDuel> duels() {
        if (duels == null) {
            final List<QuestEventDuel> made = quest().getDuelsManager().generateDuels();
            duels = made == null ? List.of() : made;
        }
        return duels;
    }

    private QuestDuels duelsPage() {
        final QuestController quest = quest();
        final Localizer text = Localizer.getInstance();
        final List<QuestDuelRow> rows = new ArrayList<>();
        final List<QuestEventDuel> offered = duels();
        for (int i = 0; i < offered.size(); i++) {
            final QuestEventDuel duel = offered.get(i);
            // The random opponent is a surprise, so nothing of its deck shows
            final boolean random = !duel.showDifficulty();
            rows.add(new QuestDuelRow(i, duel.getTitle(), random ? 0 : duel.getDifficulty().ordinal() + 1, duel.getDescription(),
                    random ? "" : DeckCatalog.colors(duel.getEventDeck()), random ? null : face(duel.getEventDeck()), random));
        }
        final QuestAchievements record = quest.getAchievements();
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final List<QuestPetChoice> pets = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        String next = null;
        // Pets, the plant, charms and challenges are Fantasy mode's only, as desktop's Duels screen shows them
        if (fantasy) {
            for (int slot = 0; slot < QuestController.MAX_PET_SLOTS; slot++) {
                final List<String> owned = quest.getPetsStorage().getAvaliablePets(slot, quest.getAssets()).stream().map(QuestPetController::getName).toList();
                if (!owned.isEmpty()) {
                    pets.add(new QuestPetChoice(slot, owned, quest.getSelectedPet(slot)));
                }
            }
            lengths.addAll(matchLengths());
            final int wins = QuestUtil.nextChallengeInWins();
            next = wins == 0 ? text.getMessage("lblnextChallengeInWins0") : wins == 1 ? text.getMessage("lblnextChallengeInWins1")
                    : text.getMessage("lblnextChallengeInWins2").replace("%n", String.valueOf(wins));
        }
        final Deck deck = QuestUtil.getCurrentDeck();
        String problem = deck == null ? text.getMessage("lblBuildAndSelectaDeck") : null;
        if (deck != null && FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            problem = QuestUtil.getDeckConformanceProblemsBeforeGame(deck);
        }
        return new QuestDuels(rows, record.getWin(), record.getLost(), record.getWinStreakCurrent(), record.getWinStreakBest(), next,
                pets, lengths, quest.getMatchLength(), deck == null ? "" : deck.getName(), problem, canUnlock());
    }

    /** Whether Unlock Sets is offered, as desktop shows its button. */
    private static boolean canUnlock() {
        return quest().getUnlocksTokens() > 0 && quest().getWorldFormat() == null;
    }

    /** The match lengths the charms allow: one with the Charm of Vim, five with the Charm of Vigor. */
    private static List<Integer> matchLengths() {
        final QuestAssets assets = quest().getAssets();
        final List<Integer> lengths = new ArrayList<>();
        if (assets.hasItem(QuestItemType.CHARM_VIM)) {
            lengths.add(1);
        }
        lengths.add(3);
        if (assets.hasItem(QuestItemType.CHARM)) {
            lengths.add(5);
        }
        return lengths;
    }

    /** The opponent's creature of highest mana value, else its first spell, as the picture of a duel. */
    private static String face(final Deck deck) {
        PaperCard best = null;
        PaperCard firstSpell = null;
        for (final PaperCard card : deck.getMain().toFlatList()) {
            final CardType type = card.getRules().getType();
            if (firstSpell == null && !type.isLand()) {
                firstSpell = card;
            }
            if (type.isCreature() && (best == null || card.getRules().getManaCost().getCMC() > best.getRules().getManaCost().getCMC())) {
                best = card;
            }
        }
        final PaperCard face = best != null ? best : firstSpell;
        return face == null ? null : face.getImageKey(false);
    }

    CampaignBar bar() {
        final QuestController quest = quest();
        final Localizer text = Localizer.getInstance();
        final List<Balance> balances = new ArrayList<>();
        balances.add(new Balance("ICO_QUEST_COINSTACK", (int) quest.getAssets().getCredits(), text.getMessage("lblCredits")));
        // Life is bought and lost only in Fantasy mode
        if (quest.getMode() == QuestMode.Fantasy) {
            balances.add(new Balance("ICO_QUEST_LIFE", quest.getAssets().getLife(quest.getMode()), text.getMessage("lblLife")));
        }
        return new CampaignBar(quest.getName(), quest.getRank() + " · " + world(quest.getWorld() == null ? null : quest.getWorld().getName()), balances);
    }

    @Override
    public Reward reward() {
        return reward;
    }

    @Override
    public void claim(final Host host) {
        reward = null;
    }

    /** Starts the duel at an index of the list the page was sent, as desktop's Start does. */
    private void duel(final int index, final Host host) {
        final List<QuestEventDuel> offered = duels();
        if (index >= 0 && index < offered.size()) {
            fight(offered.get(index), host);
        }
    }

    /** The challenges on offer, drawn again as desktop's page does each time it is shown, which draws only where there is room. */
    private QuestChallenges challengesPage() {
        final QuestController quest = quest();
        final QuestAchievements record = quest.getAchievements();
        final List<QuestChallengeRow> rows = new ArrayList<>();
        quest.regenerateChallenges();
        for (final String id : record.getCurrentChallenges()) {
            final QuestEventChallenge c = quest.getChallenges().get(id);
            if (c == null) {
                continue;
            }
            rows.add(new QuestChallengeRow(c.getId(), c.getTitle(), c.getDifficulty() == null ? 0 : c.getDifficulty().ordinal() + 1,
                    c.getDescription(), face(c.getEventDeck()), c.getAILife(), c.getHumanLife(), c.getCreditsReward(),
                    c.getCardRewardList() == null ? List.of() : c.getCardRewardList().stream().map(InventoryItem::getName).toList(),
                    c.isRepeatable(), c.getHumanExtraCards(), c.getAiExtraCards(), c.getHumanDeck() != null));
        }
        // Desktop hides the zeppelin and the next-challenge line in Classic mode, though it offers the challenges
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final int max = Math.max(0, Math.min(5, record.getWin() / quest.getTurnsToUnlockChallenge() - record.getChallengesPlayed()));
        final int wins = QuestUtil.nextChallengeInWins();
        final Localizer text = Localizer.getInstance();
        final String next = fantasy && rows.isEmpty() ? wins == 1 ? text.getMessage("lblnextChallengeInWins1")
                : text.getMessage("lblnextChallengeInWins2").replace("%n", String.valueOf(wins)) : null;
        return new QuestChallenges(rows, rows.size(), max, next, fantasy && quest.getAssets().hasItem(QuestItemType.ZEPPELIN),
                quest.getAssets().getItemLevel(QuestItemType.ZEPPELIN) == 2);
    }

    /** The tournaments on offer, drawn again where old ones have aged out as desktop's screen does, or the one entered with its bracket. */
    private static QuestTournaments tournamentsPage() {
        final QuestAchievements record = quest().getAchievements();
        if (record.getCurrentDraft() == null) {
            record.generateDrafts();
        }
        final QuestEventDraft entered = record.getCurrentDraft();
        final List<QuestTournamentRow> offered = new ArrayList<>();
        if (entered == null) {
            for (final QuestEventDraft d : record.getDraftEvents()) {
                // canEnter is true when the credits are short of the fee
                final List<CardEdition> sets = Arrays.stream(d.getBoosterConfiguration()).map(FModel.getMagicDb().getEditions()::get).toList();
                offered.add(new QuestTournamentRow(d.getTitle(), sets.stream().map(CardEdition::getName).toList(),
                        sets.stream().map(WebGuiBase::boosterImage).toList(), d.getEntryFee(), !d.canEnter()));
            }
        }
        final List<Integer> placings = IntStream.rangeClosed(1, 4).map(record::getWinsForPlace).boxed().toList();
        return new QuestTournaments(offered, record.getDraftTokens(), placings, entered == null ? null : bracket(entered));
    }

    /** The standings as rounds: places 0-7 are the first round's seats, 8-11 the second's, 12-13 the final's and 14 the winner's, and place p's winner goes to place 8 + p / 2. */
    private static QuestBracket bracket(final QuestEventDraft draft) {
        final String[] standings = draft.getStandings();
        final List<List<QuestSeat>> rounds = new ArrayList<>();
        for (final int[] round : new int[][] {{0, 8}, {8, 12}, {12, 14}, {14, 15}}) {
            final List<QuestSeat> seats = new ArrayList<>();
            for (int p = round[0]; p < round[1]; p++) {
                seats.add(seat(draft, standings, p));
            }
            rounds.add(seats);
        }
        String next = null;
        final int you = Arrays.asList(standings).lastIndexOf(QuestEventDraft.HUMAN);
        if (draft.isStarted() && draft.playerHasMatchesLeft() && you >= 0) {
            // The seat paired with the player's, whose holder may still be decided by a computer match
            final String opponent = standings[you ^ 1];
            next = QuestEventDraft.UNDETERMINED.equals(opponent) ? "" : seat(draft, standings, you ^ 1).name();
        }
        return new QuestBracket(draft.getTitle(), rounds, next, draft.getPlacementString(), draft.isStarted(),
                quest().getDraftDecks().get(QuestEventDraft.DECK_NAME) != null);
    }

    private static QuestSeat seat(final QuestEventDraft draft, final String[] standings, final int place) {
        final String held = standings[place];
        if (QuestEventDraft.UNDETERMINED.equals(held)) {
            return new QuestSeat(null, false, "open");
        }
        final boolean you = QuestEventDraft.HUMAN.equals(held);
        // The player is named by the browser, which knows the name its matches are played under
        final String name = you ? "" : draft.getAINames()[Integer.parseInt(held) - 1];
        final String after = place == 14 ? held : standings[8 + place / 2];
        return new QuestSeat(name, you, QuestEventDraft.UNDETERMINED.equals(after) ? "open" : after.equals(held) ? "won" : "out");
    }

    /** Enters a tournament on offer, as desktop's Enter does: its fee is paid, its packs dealt, and its draft opened. Answers why it cannot, or null. */
    private static String enter(final String title, final Host host) {
        final QuestAchievements record = quest().getAchievements();
        if (title == null || record.getCurrentDraft() != null || record.getDraftEvents() == null) {
            return null;
        }
        for (final QuestEventDraft offered : record.getDraftEvents()) {
            if (title.equals(offered.getTitle())) {
                if (offered.canEnter()) {
                    return Localizer.getInstance().getMessage("lblWebQuestCreditsShort", offered.getEntryFee() - quest().getAssets().getCredits());
                }
                final BoosterDraft draft = offered.enter();
                quest().save();
                host.startDraft(draft);
                return null;
            }
        }
        return null;
    }

    /** Leaves a tournament whose draft was not finished: its fee comes back and it is no longer offered, as desktop's leaving the draft does. */
    void cancelDraft() {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null) {
            return;
        }
        quest().getAssets().addCredits(draft.getEntryFee());
        quest().getAchievements().deleteDraft(draft);
        quest().save();
    }

    /** Keeps a tournament's finished draft as Quest keeps it, and answers the storage its deck is built in. */
    IStorage<DeckGroup> drafted(final DeckGroup group) {
        QuestDraftUtils.completeDraft(group);
        return quest().getDraftDecks();
    }

    /** Spends a token on a tournament of a format the player chooses, as desktop's Spend Token asks it. */
    private static void spendToken() {
        final QuestAchievements record = quest().getAchievements();
        if (record.getDraftTokens() <= 0) {
            return;
        }
        final Localizer text = Localizer.getInstance();
        final List<QuestDraftFormat> formats = QuestEventDraft.getAvailableFormats(quest());
        if (formats.isEmpty()) {
            SOptionPane.showErrorDialog(text.getMessage("lblNoAvailableDraftsMessage"), text.getMessage("lblNoAvailableDrafts"));
            return;
        }
        final QuestDraftFormat format = SGuiChoose.oneOrNone(text.getMessage("lblChooseDraftFormat"), formats);
        if (format == null) {
            return;
        }
        // Made only to show its fee, as desktop does; the token makes the tournament itself
        final QuestEventDraft shown = QuestEventDraft.getDraftOrNull(quest(), format);
        if (shown != null && SOptionPane.showConfirmDialog(text.getMessage("lblEntryFeeOfDraftTournament") + shown.getEntryFee()
                + text.getMessage("lblWouldLikeCreateTournament"), text.getMessage("lblCreatingDraftTournament"))) {
            record.spendDraftToken(format);
        }
    }

    /** Starts the tournament entered once its deck is legal, as desktop's Start Tournament does. Answers why it cannot, or null. */
    private static String startTournament() {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        final DeckGroup decks = quest().getDraftDecks().get(QuestEventDraft.DECK_NAME);
        if (draft == null || draft.isStarted()) {
            return null;
        }
        if (decks == null) {
            return Localizer.getInstance().getMessage("lblWebQuestDraftLost");
        }
        final String problem = GameType.QuestDraft.getDeckFormat().getDeckConformanceProblem(decks.getHumanDeck());
        if (problem != null && FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return Localizer.getInstance().getMessage("lblDeck") + " " + problem;
        }
        draft.start();
        return null;
    }

    /** Decides the computer's matches up to the player's without a game, as mobile does, and starts the player's. Answers why it cannot, or null. */
    private String nextMatch(final Host host) {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null || !draft.isStarted() || reward != null) {
            return null;
        }
        // injectRandomMatchOutcome answers true for ever once the player is out, so the player's matches left end the loop
        while (draft.playerHasMatchesLeft() && QuestDraftUtils.injectRandomMatchOutcome(false)) {
        }
        quest().save();
        if (!draft.playerHasMatchesLeft()) {
            return null;
        }
        final String illegal = QuestDraftUtils.getDeckLegality();
        if (illegal != null) {
            return illegal;
        }
        view = null;
        controller = null;
        tournamentDecided = false;
        host.startMatch(() -> {
            final PreparedMatch match = QuestDraftUtils.prepareNextMatch();
            if (match != null) {
                tournamentOpponent = match.players().stream().filter(p -> p != match.human()).findFirst().orElseThrow().getPlayer().getName();
            }
            return match;
        }, () -> tournamentOpponent = null);
        return null;
    }

    /** A tournament game, as desktop's QuestDraftWinLose has it: the bracket learns the winner once the match is over, and nothing goes on the quest's record. */
    private CampaignResult tournamentGameOver(final GameView hostGame, final String opponent) {
        final boolean over = hostGame.isMatchOver();
        final String winner = hostGame.getWinningPlayerName();
        final boolean won = winner != null && !opponent.equals(winner);
        if (over && winner != null) {
            // setWinner takes any name not a computer's as the player's, and the player may share a name with a computer
            quest().getAchievements().getCurrentDraft().setWinner(won ? QuestEventDraft.HUMAN : opponent);
            quest().save();
            tournamentDecided = true;
        }
        final Localizer text = Localizer.getInstance();
        final List<ResultButton> buttons = over
                ? List.of(new ResultButton(text.getMessage("lblWebQuestContinueTournament"), "leave", true))
                : List.of(new ResultButton(text.getMessage("btnContinue"), "nextGame", true),
                        new ResultButton(text.getMessage("lblWebQuestForfeitTournament"), "forfeit", false));
        result = new CampaignResult(won, over, null, buttons);
        return result;
    }

    /** Leaves the tournament entered, as desktop's Leave Tournament or Collect Prizes does: prizes once it has started, the offer to keep the draft, and the pool added to the quest. */
    private void endTournament() {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null) {
            return;
        }
        if (quest().getDraftDecks().get(QuestEventDraft.DECK_NAME) == null) {
            // Its draft was never finished, so there is no pool to keep
            cancelDraft();
            return;
        }
        final WebQuestView shown = new WebQuestView();
        if (draft.isStarted()) {
            prizes(draft, shown);
        }
        final Localizer text = Localizer.getInstance();
        if (SOptionPane.showOptionDialog(text.getMessage("lblWouldLikeSaveDraft"), text.getMessage("lblSaveDraft") + "?", SOptionPane.QUESTION_ICON,
                List.of(text.getMessage("lblYes"), text.getMessage("lblNo")), 0) == 0) {
            draft.saveToRegularDraft();
        }
        draft.addToQuestDecks();
        if (!shown.steps.isEmpty()) {
            final List<RewardStep> steps = new ArrayList<>(reward == null ? List.of() : reward.steps());
            steps.addAll(shown.steps);
            reward = new Reward(steps);
        }
    }

    /** The prizes for the player's placing, as desktop's endTournamentAndAwardPrizes gives them, shown as reward steps. */
    private static void prizes(final QuestEventDraft draft, final WebQuestView shown) {
        final Localizer text = Localizer.getInstance();
        final String placing = text.getMessage("lblForPlacing") + draft.getPlacementString();
        final QuestDraftPrizes prizes = draft.collectPrizes();
        // Only the first four places win anything
        if (prizes == null) {
            return;
        }
        if (prizes.hasCredits()) {
            shown.showMessage(placing + text.getMessage("lblHaveBeAward") + QuestUtil.formatCredits(prizes.credits) + " " + text.getMessage("lblCredits") + "!",
                    text.getMessage("lblCreditsAwarded"), FSkinProp.ICO_QUEST_GOLD);
        }
        if (prizes.hasIndividualCards()) {
            shown.showCards(text.getMessage("lblTournamentReward"), prizes.individualCards);
        }
        if (prizes.hasBoosterPacks()) {
            final String plural = prizes.boosterPacks.size() == 1 ? "" : "s";
            shown.showMessage(placing + text.getMessage("lblHaveBeAward") + prizes.boosterPacks.size() + " " + text.getMessage("lblBoosterPack") + plural + "!",
                    text.getMessage("lblBoosterPack") + plural + " " + text.getMessage("lblAwarded"), FSkinProp.ICO_QUEST_BOX);
            final List<PaperCard> cards = new ArrayList<>();
            for (final BoosterPack pack : prizes.boosterPacks) {
                cards.addAll(pack.getCards());
            }
            shown.showCards(text.getMessage("lblTournamentReward"), cards);
        }
        if (prizes.selectRareFromSets()) {
            final PaperCard card = GuiBase.getInterface().chooseCard(text.getMessage("lblSelectACard"), text.getMessage("lblSelectKeepCard"), prizes.selectRareCards);
            if (card != null) {
                prizes.addSelectedCard(card);
                shown.showMessage("'" + card.getDisplayName() + "' " + text.getMessage("lblAddToCollection"), text.getMessage("lblCardAdded"), FSkinProp.ICO_QUEST_STAKES);
            }
        }
        if (draft.getPlayerPlacement() == 1) {
            shown.showMessage(placing + text.getMessage("lblHaveBeAwardToken"), text.getMessage("lblBonusToken"), FSkinProp.ICO_QUEST_NOTES);
            quest().getAchievements().addDraftToken();
        }
        quest().save();
    }

    /** The bazaar with one stall open: Fantasy mode's only, as desktop and mobile offer it. */
    private static QuestBazaar bazaar(final String wanted) {
        final QuestController quest = quest();
        if (quest.getMode() != QuestMode.Fantasy) {
            return new QuestBazaar(List.of(), null, List.of());
        }
        final QuestBazaarManager bazaar = quest.getBazaar();
        final List<QuestStallRow> stalls = new ArrayList<>();
        for (final String name : bazaar.getStallNames()) {
            final QuestStallDefinition stall = bazaar.getStall(name);
            stalls.add(new QuestStallRow(stall.getName(), stall.getDisplayName(), stall.getFluff(), stall.getIcon().name()));
        }
        final String open = stalls.stream().anyMatch(s -> s.name().equals(wanted)) ? wanted : stalls.isEmpty() ? null : stalls.get(0).name();
        final List<QuestItemRow> items = new ArrayList<>();
        if (open != null) {
            final QuestAssets assets = quest.getAssets();
            for (final IQuestBazaarItem item : bazaar.getItems(quest, open)) {
                items.add(itemRow(item, assets));
            }
        }
        return new QuestBazaar(stalls, open, items);
    }

    private static QuestItemRow itemRow(final IQuestBazaarItem item, final QuestAssets assets) {
        String icon = null;
        String card = null;
        if (item.getIcon(assets) instanceof WebGuiBase.WebSkinImage image) {
            if (image.prop() != null) {
                icon = image.prop().name();
            } else if (image.path() != null) {
                // A pet's picture is its token's, which the image route serves by the token's key
                final String file = new File(image.path()).getName();
                card = "t:" + (file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file);
            }
        }
        // The description is written for desktop's label, with line breaks as markup
        final String description = item.getPurchaseDescription(assets).replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
        if (item instanceof QuestPetController pet) {
            // A pet's purchase description repeats its stats, which the row carries as now and next
            return new QuestItemRow(item.getPurchaseName(), pet.getDescription(), icon, card, item.getBuyingPrice(assets), assets.getPetLevel(pet.getSaveFileKey()),
                    pet.getMaxLevel(), pet.getStats(assets), pet.getUpgradedStats(assets));
        }
        final QuestItemBasic basic = (QuestItemBasic) item;
        return new QuestItemRow(item.getPurchaseName(), description, icon, card, item.getBuyingPrice(assets), assets.getItemLevel(basic.getItemType()),
                basic.getMaxLevel(), null, null);
    }

    /** Buys an item of a stall, refusing first when the credits are short, since Quest's own purchase then does nothing. */
    private static String buy(final String stall, final String name) {
        final QuestController quest = quest();
        if (quest.getMode() != QuestMode.Fantasy || stall == null || !quest.getBazaar().getStallNames().contains(stall)) {
            return null;
        }
        for (final IQuestBazaarItem item : quest.getBazaar().getItems(quest, stall)) {
            if (item.getPurchaseName().equals(name)) {
                final int price = item.getBuyingPrice(quest.getAssets());
                if (price < 0) {
                    return null;
                }
                final long short_ = price - quest.getAssets().getCredits();
                if (short_ > 0) {
                    return Localizer.getInstance().getMessage("lblWebQuestCreditsShort", short_);
                }
                QuestUtil.buyQuestItem(item);
                return null;
            }
        }
        return null;
    }

    /** Starts a duel or a challenge, as desktop's Start does. */
    private void fight(final QuestEvent duel, final Host host) {
        // A reward still to be shown belongs to the last match, and the next match's would replace it
        if (reward != null) {
            return;
        }
        QuestUtil.setEvent(duel);
        if (!QuestUtil.canStartGame()) {
            return;
        }
        // What desktop's start does in the background before the match
        quest().getDuelsManager().randomizeOpponents();
        quest().setCurrentEvent(duel);
        quest().save();
        view = new WebQuestView();
        controller = null;
        final String face = face(duel.getEventDeck());
        host.startMatch(() -> {
            final PreparedMatch match = QuestUtil.prepareGame();
            match.players().get(1).getPlayer().setAvatarCardImageKey(face);
            return match;
        }, () -> {
            quest().setCurrentEvent(null);
            view = null;
        });
    }

    /** Quest's own result controller is made for each game, as desktop makes one per game, and the latest one records the match when it is left. */
    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        final String opponent = tournamentOpponent;
        if (opponent != null && hostGame != null) {
            return tournamentGameOver(hostGame, opponent);
        }
        final WebQuestView shown = view;
        if (shown == null || hostGame == null || quest().getCurrentEvent() == null) {
            return null;
        }
        shown.newGame();
        controller = new QuestWinLoseController(hostGame, shown);
        controller.showRewards();
        final boolean over = hostGame.isMatchOver();
        final List<ResultButton> buttons = new ArrayList<>();
        if (shown.getBtnContinue().isVisible()) {
            buttons.add(new ResultButton(shown.getBtnContinue().getText(), "nextGame", true));
        }
        if (shown.getBtnQuit().isVisible()) {
            buttons.add(new ResultButton(shown.getBtnQuit().getText(), over ? "leave" : "quit", buttons.isEmpty()));
        }
        result = new CampaignResult(GamePlayerUtil.getQuestPlayer().getName().equals(hostGame.getWinningPlayerName()), over, null, buttons);
        return result;
    }

    /** The rewards the controller works out may ask the player questions, which belong after the result screen. */
    @Override
    public void resultSent() {
        final WebQuestView shown = view;
        final Runnable rest = shown == null ? null : shown.takeScript();
        if (rest != null) {
            rest.run();
        }
    }

    @Override
    public CampaignResult result() {
        return result;
    }

    /** Leaving records the match, as desktop's Quit does, and the match's steps become the reward shown over the Duels page. */
    @Override
    public void left() {
        final String opponent = tournamentOpponent;
        if (opponent != null) {
            tournamentOpponent = null;
            result = null;
            // A match left before it is decided is forfeit, as desktop's Forfeit Tournament has it
            if (!tournamentDecided) {
                quest().getAchievements().getCurrentDraft().setWinner(opponent);
                QuestDraftUtils.cancelFurtherMatches();
                quest().save();
            }
            return;
        }
        final QuestWinLoseController last = controller;
        final WebQuestView shown = view;
        controller = null;
        view = null;
        result = null;
        if (last != null) {
            last.actionOnQuit();
        } else if (shown != null) {
            quest().setCurrentEvent(null);
        }
        if (shown != null && !shown.steps.isEmpty()) {
            reward = new Reward(List.copyOf(shown.steps));
        }
        // The opponents were shuffled when the duel started, so the next page offers new ones
        duels = null;
    }

    /** Cards Forge showed the host, such as a booster just bought: added to what the reveal has yet to show. */
    void shown(final String title, final List<PaperCard> cards) {
        final List<RewardStep> steps = new ArrayList<>(reward == null ? List.of() : reward.steps());
        steps.add(WebQuestView.cardsStep(title, cards, false));
        reward = new Reward(steps);
    }

    /** What a card sells for, as the shop's own sale works it out. */
    private static int salePrice(final PaperCard card, final double multiplier) {
        return Math.max(Math.min((int) (multiplier * QuestSpellShop.getCardValue(card)), quest().getCards().getSellPriceLimit()), 1);
    }

    /** How many of the quest's decks hold a card, in either section. */
    private static Map<PaperCard, Integer> decksUsing() {
        final Map<PaperCard, Integer> using = new HashMap<>();
        for (final Deck deck : quest().getMyDecks()) {
            final Set<PaperCard> cards = new HashSet<>();
            deck.getMain().forEach(e -> cards.add(e.getKey()));
            if (deck.has(DeckSection.Sideboard)) {
                deck.get(DeckSection.Sideboard).forEach(e -> cards.add(e.getKey()));
            }
            cards.forEach(c -> using.merge(c, 1, Integer::sum));
        }
        return using;
    }

    /** A card's key in a trade: its printing, and whether it is foil, which the image key does not say and the price does. */
    private static String key(final PaperCard card) {
        return card.getImageKey(false) + (card.isFoil() ? "|foil" : "");
    }

    private static int copies(final ItemPool<? extends InventoryItem> pool) {
        int n = 0;
        for (final Map.Entry<? extends InventoryItem, Integer> e : pool) {
            if (e.getKey() instanceof PaperCard) {
                n += e.getValue();
            }
        }
        return n;
    }

    private static List<PaperCard> cardsIn(final ItemPool<? extends InventoryItem> pool) {
        final List<PaperCard> cards = new ArrayList<>();
        pool.forEach(e -> {
            if (e.getKey() instanceof PaperCard card) {
                cards.add(card);
            }
        });
        return cards;
    }

    /** A page of the shop's cards for sale, each with its price, stock and copies owned, or of the player's cards, each with its sale price, copies and decks. */
    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        final boolean shop = "shop".equals(q.source());
        final ItemPool<InventoryItem> stock = quest().getCards().getShopList();
        final ItemPool<PaperCard> owned = quest().getCards().getCardpool();
        // Without these the shop's statics are unset, and every card would sell for 1 credit
        final double multiplier = QuestSpellShop.updateMultiplier();
        final Map<PaperCard, Integer> using = shop ? Map.of() : decksUsing();
        final CataloguePage page = CardCatalog.of(shop ? cardsIn(stock) : cardsIn(owned)).query(q.request(), new CardCatalog.Query(q.text(),
                q.colours(), q.type(), q.filters(), q.sort(), q.offset(), true), c -> null, null, name -> 0, c -> shop
                ? new CardCatalog.Extra(null, QuestSpellShop.getCardValue(c), stock.count(c), owned.count(c), null, key(c))
                : new CardCatalog.Extra(quest().getCards().isNew(c) ? Boolean.TRUE : null, salePrice(c, multiplier), owned.count(c), null,
                        !using.containsKey(c) ? null : using.get(c) == 1 ? Localizer.getInstance().getMessage("lblWebQuestInOneDeck")
                        : Localizer.getInstance().getMessage("lblWebQuestInDecks", using.get(c)), key(c)));
        // The page says which list it is of, so the browser never shows one list's cards as another's
        return new CataloguePage(page.request(), page.rows(), page.total(), page.offset(), page.hiddenBySwitch(), page.ranked(), q.source());
    }

    /** The shop's two lists, what selling pays, the products for sale, and the copies Sell All Extras would sell. */
    @Override
    public Trading trading() {
        final Localizer text = Localizer.getInstance();
        final ItemPool<InventoryItem> stock = quest().getCards().getShopList();
        final ItemPool<PaperCard> owned = quest().getCards().getCardpool();
        final double multiplier = QuestSpellShop.updateMultiplier();
        final int limit = quest().getCards().getSellPriceLimit();
        // Mobile's line, on one line
        final String note = (text.getMessage("lblSellCardsAt") + " " + new DecimalFormat("#.##").format(multiplier * 100) + text.getMessage("lblTheirValue")
                + (limit < Integer.MAX_VALUE ? String.format(text.getMessage("lblMaximumSellingCredits"), limit) : "")).replace('\n', ' ').trim();
        final List<Product> products = new ArrayList<>();
        stock.forEach(e -> {
            if (!(e.getKey() instanceof PaperCard)) {
                products.add(new Product("p:" + e.getKey().getName(), e.getKey().getItemType(), e.getKey().getName(), e.getKey().getImageKey(false),
                        QuestSpellShop.getCardValue(e.getKey())));
            }
        });
        final ItemPool<InventoryItem> ownedItems = new ItemPool<>(InventoryItem.class);
        ownedItems.addAllOfType(owned);
        final ItemPool<InventoryItem> extras = QuestSpellShop.extras(ownedItems);
        final List<CatalogueRow> extraRows = CardCatalog.rows(cardsIn(extras),
                c -> new CardCatalog.Extra(null, salePrice(c, multiplier), extras.count(c), null, null, key(c)));
        // Copies, not cards, so that a trade of part of a stack changes them, which is what tells the page its lists changed
        return new Trading(List.of(new TradeList("shop", copies(stock)), new TradeList("inventory", copies(owned))), note, null,
                products, extraRows);
    }

    /** Buys picks from the shop, or sells picks of the player's cards, each up to what its list holds. Answers why a purchase cannot be made, or null. */
    @Override
    public String trade(final String source, final List<TradePick> picks) {
        final boolean buying = "shop".equals(source);
        final ItemPool<InventoryItem> stock = quest().getCards().getShopList();
        final ItemPool<InventoryItem> chosen = new ItemPool<>(InventoryItem.class);
        for (final TradePick pick : picks == null ? List.<TradePick>of() : picks) {
            final InventoryItem item = buying ? find(stock, pick.key()) : find(quest().getCards().getCardpool(), pick.key());
            final int held = item == null ? 0 : buying ? stock.count(item) : quest().getCards().getCardpool().count((PaperCard) item);
            if (held > 0 && pick.count() > 0) {
                chosen.add(item, Math.min(pick.count(), held));
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        if (buying) {
            final long short_ = QuestSpellShop.getTotalBuyCost(chosen) - quest().getAssets().getCredits();
            if (short_ > 0) {
                // Quest's own sentence
                return "You need " + short_ + " more credits to purchase the following "
                        + SItemManagerUtil.getItemDisplayString(chosen.toFlatList(), 1, true).toLowerCase() + ".\n" + SItemManagerUtil.buildDisplayList(chosen);
            }
            QuestSpellShop.buyItems(chosen);
            // The shop's list widget takes what was bought out of the stock on desktop and mobile
            stock.removeAll(chosen);
        } else {
            QuestSpellShop.updateMultiplier();
            QuestSpellShop.sellItems(chosen);
            // As the shop's list widget puts what was sold back on sale
            stock.addAll(chosen);
        }
        quest().save();
        return null;
    }

    /** A card of a list by its key, or a product by "p:" and its name. */
    private static InventoryItem find(final ItemPool<? extends InventoryItem> pool, final String key) {
        if (key == null) {
            return null;
        }
        for (final Map.Entry<? extends InventoryItem, Integer> e : pool) {
            final InventoryItem item = e.getKey();
            if (item instanceof PaperCard card ? key(card).equals(key) : ("p:" + item.getName()).equals(key)) {
                return item;
            }
        }
        return null;
    }

    /** The quest's record, purse and collection, its tournament placings, and in Fantasy mode what it owns of the bazaar. */
    @Override
    public CampaignStats stats(final String scope) {
        final QuestController quest = quest();
        final QuestAchievements record = quest.getAchievements();
        final QuestAssets assets = quest.getAssets();
        final Localizer l = Localizer.getInstance();
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final List<Figure> figures = new ArrayList<>(List.of(
                new Figure(l.getMessage("lblWins"), record.getWin(), null),
                new Figure(l.getMessage("lblLosses"), record.getLost(), null),
                new Figure(l.getMessage("lblWinStreak"), record.getWinStreakCurrent(), null),
                new Figure(l.getMessage("lblWebQuestBestStreak"), record.getWinStreakBest(), null),
                new Figure(l.getMessage("lblCredits"), (int) Math.min(Integer.MAX_VALUE, assets.getCredits()), null)));
        if (fantasy) {
            figures.add(new Figure(l.getMessage("lblLife"), assets.getLife(quest.getMode()), null));
        }
        figures.add(new Figure(l.getMessage("lblCards"), assets.getCardPool().countAll(), null));
        final int rankUp = FModel.getQuestPreferences().getPrefInt(DifficultyPrefs.WINS_RANKUP, record.getDifficulty());
        figures.add(new Figure(l.getMessage("lblWebQuestWinsToLevel"), rankUp - record.getWin() % rankUp, null));
        final List<StatTable> tables = new ArrayList<>();
        final List<List<String>> placings = new ArrayList<>();
        for (int place = 1; place <= PLACES.length; place++) {
            placings.add(List.of(l.getMessage(PLACES[place - 1]), String.valueOf(record.getWinsForPlace(place))));
        }
        tables.add(new StatTable(List.of(l.getMessage("lblPastResults"), l.getMessage("lblWebQuestTimesHead")), placings));
        if (fantasy) {
            tables.add(new StatTable(List.of(l.getMessage("lblBazaar"), l.getMessage("lblWebQuestLevelHead")), owned(quest)));
        }
        return new CampaignStats(l.getMessage("lblQuestStatistics"), figures, tables, List.of(), null, null);
    }

    private static final String[] PLACES = {"lblWebQuestFirstPlace", "lblWebQuestSecondPlace", "lblWebQuestThirdPlace", "lblWebQuestFourthPlace"};

    /** The bazaar's items and pets the quest owns, each with its level of its most; an item missing from every stall has reached its most. */
    private static List<List<String>> owned(final QuestController quest) {
        final QuestAssets assets = quest.getAssets();
        final Map<QuestItemType, Integer> most = new HashMap<>();
        for (final String stall : quest.getBazaar().getStallNames()) {
            for (final IQuestBazaarItem item : quest.getBazaar().getItems(quest, stall)) {
                if (item instanceof QuestItemBasic basic) {
                    most.put(basic.getItemType(), basic.getMaxLevel());
                }
            }
        }
        final Localizer l = Localizer.getInstance();
        final List<List<String>> rows = new ArrayList<>();
        for (final QuestItemType type : QuestItemType.values()) {
            if (assets.hasItem(type)) {
                final int level = assets.getItemLevel(type);
                rows.add(List.of(type.getKey(), l.getMessage("lblWebQuestLevelOf", level, most.getOrDefault(type, level))));
            }
        }
        for (int slot = 0; slot < QuestController.MAX_PET_SLOTS; slot++) {
            for (final QuestPetController pet : quest.getPetsStorage().getAvaliablePets(slot, assets)) {
                rows.add(List.of(pet.getName(), l.getMessage("lblWebQuestLevelOf", assets.getPetLevel(pet.getSaveFileKey()), pet.getMaxLevel())));
            }
        }
        return rows;
    }

    /** A preference as desktop's page lists it, by its label's and group's keys; column is the difficulty a difficulty's own value is for. */
    private record PrefField(QPref pref, String label, String group, String column) {
    }

    /** Desktop's preferences in its order (VSubmenuQuestPrefs), with the difficulty table's values one field each. */
    private static final List<PrefField> PREFS = prefFields();

    private static List<PrefField> prefFields() {
        final List<PrefField> fields = new ArrayList<>();
        fields.add(new PrefField(QPref.WORLD_RULES_CONFORMANCE, "lblWorldRulesConformance", "lblQuestGameSettings", null));
        final Object[][] rewards = {{QPref.REWARDS_BASE, "lblBaseWinnings"}, {QPref.REWARDS_UNDEFEATED, "lblNoLosses"}, {QPref.REWARDS_POISON, "lblPoisonWin"},
                {QPref.REWARDS_MILLED, "lblMillingWin"}, {QPref.REWARDS_MULLIGAN0, "lblMulligan0Win"}, {QPref.REWARDS_ALTERNATIVE, "lblAlternativeWin"},
                {QPref.REWARDS_WINS_MULTIPLIER, "lblBonusMultiplierperWin"}, {QPref.REWARDS_WINS_MULTIPLIER_MAX, "lblMaxWinsforMultiplier"},
                {QPref.REWARDS_TURN15, "lblWinbyTurn15"}, {QPref.REWARDS_TURN10, "lblWinbyTurn10"}, {QPref.REWARDS_TURN5, "lblWinbyTurn5"},
                {QPref.REWARDS_TURN1, "lblFirstTurnWin"}, {QPref.REWARDS_HEALTH_DIFF_MAX, "lblMaxLifeDiffBonus"},
                {QPref.EXCLUDE_PROMOS_FROM_POOL, "lblExcludePromosFromRewardPool"}};
        add(fields, "lblRewards", rewards);
        final String[][] perDifficulty = {{"WINS_BOOSTER", "lblWinsforBooster"}, {"WINS_RANKUP", "lblWinsforRankIncrease"},
                {"WINS_MEDIUMAI", "lblWinsforMediumAI"}, {"WINS_HARDAI", "lblWinsforHardAI"}, {"WINS_EXPERTAI", "lblWinsforExpertAI"},
                {"STARTING_COMMONS", "lblStartingCommons"}, {"STARTING_UNCOMMONS", "lblStartingUncommons"}, {"STARTING_RARES", "lblStartingRares"},
                {"STARTING_CREDITS", "lblStartingCredits"}};
        final String[] suffixes = {"EASY", "MEDIUM", "HARD", "EXPERT"};
        for (final String[] row : perDifficulty) {
            for (int d = 0; d < suffixes.length; d++) {
                fields.add(new PrefField(QPref.valueOf(row[0] + "_" + suffixes[d]), row[1], "lblDifficultyAdjustments", DIFFICULTIES[d]));
            }
        }
        add(fields, "lblDifficultyAdjustments", new Object[][] {{QPref.WINS_NEW_CHALLENGE, "lblWinsforNewChallenge"},
                {QPref.STARTING_SNOW_LANDS, "lblStartingSnowLands"}, {QPref.STARTING_POOL_COLOR_BIAS, "lblColorBias"},
                {QPref.PENALTY_LOSS, "lblPenaltyforLoss"}, {QPref.MORE_DUEL_CHOICES, "lblMoreDuelChoices"},
                {QPref.WILD_OPPONENTS_MULTIPLIER, "lblWildOpponentMultiplier"}, {QPref.WILD_OPPONENTS_NUMBER, "lblWildOpponentNumber"}});
        add(fields, "lblBoosterPackRatios", new Object[][] {{QPref.BOOSTER_COMMONS, "lblCommon"}, {QPref.BOOSTER_UNCOMMONS, "lblUncommon"},
                {QPref.BOOSTER_RARES, "lblRare"}, {QPref.SPECIAL_BOOSTERS, "lblSpecialBoosters"}});
        add(fields, "lblShopPreferences", new Object[][] {{QPref.SHOP_MAX_PACKS, "lblMaximumPacks"}, {QPref.SHOP_MIN_PACKS, "lblMinimumPacks"},
                {QPref.SHOP_STARTING_PACKS, "lblStartingPacks"}, {QPref.SHOP_WINS_FOR_ADDITIONAL_PACK, "lblWinsforPack"},
                {QPref.WINS_UNLOCK_SET, "lblWinsperSetUnlock"}, {QPref.UNLIMITED_UNLOCKING, "lblAllowFarUnlocks"},
                {QPref.UNLOCK_DISTANCE_MULTIPLIER, "lblUnlockDistanceMultiplier"}, {QPref.SHOP_SINGLES_COMMON, "lblCommonSingles"},
                {QPref.SHOP_SINGLES_UNCOMMON, "lblUncommonSingles"}, {QPref.SHOP_SINGLES_RARE, "lblRareSingles"},
                {QPref.SHOP_SELLING_PERCENTAGE_BASE, "lblCardSalePercentageBase"}, {QPref.SHOP_SELLING_PERCENTAGE_MAX, "lblCardSalePercentageCap"},
                {QPref.SHOP_MAX_SELLING_PRICE, "lblCardSalePriceCap"}, {QPref.SHOP_WINS_FOR_NO_SELL_LIMIT, "lblWinstoUncapSalePrice"},
                {QPref.PLAYSET_SIZE, "lblPlaysetSize"}, {QPref.PLAYSET_BASIC_LAND_SIZE, "lblPlaysetSizeBasicLand"},
                {QPref.PLAYSET_ANY_NUMBER_SIZE, "lblPlaysetSizeAnyNumber"}, {QPref.ITEM_LEVEL_RESTRICTION, "lblItemLevelRestriction"},
                {QPref.FOIL_FILTER_DEFAULT, "lblFoilfilterAlwaysOn"}, {QPref.RATING_FILTER_DEFAULT, "lblRatingsfilterAlwaysOn"}});
        add(fields, "lblDraftTournaments", new Object[][] {{QPref.SIMULATE_AI_VS_AI_RESULTS, "lblSimulateAIvsAIResults"},
                {QPref.WINS_NEW_DRAFT, "lblWinsforNewDraft"}, {QPref.WINS_ROTATE_DRAFT, "lblWinsperDraftRotation"},
                {QPref.DRAFT_ROTATION, "lblRotationType"}});
        return List.copyOf(fields);
    }

    private static void add(final List<PrefField> fields, final String group, final Object[][] rows) {
        for (final Object[] row : rows) {
            fields.add(new PrefField((QPref) row[0], (String) row[1], group, null));
        }
    }

    @Override
    public CampaignPrefs prefs(final String problem) {
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final Localizer l = Localizer.getInstance();
        final List<PrefRow> rows = new ArrayList<>();
        for (final PrefField f : PREFS) {
            rows.add(new PrefRow(f.pref().name(), l.getMessage(f.label()), l.getMessage(f.group()), prefs.getPref(f.pref()),
                    f.column() == null ? null : l.getMessage(f.column())));
        }
        // Neither desktop nor mobile resets Quest's preferences
        return new CampaignPrefs(l.getMessage("lblQuestPreferences"), rows, l.getMessage("lblWebQuestPrefsShared"), problem, false);
    }

    /** Checked as desktop checks it: two values are decimals and taken as they are, the rest whole numbers that Quest validates. */
    @Override
    public synchronized String setPref(final String key, final String text) {
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final Localizer l = Localizer.getInstance();
        final String value = text.trim();
        for (final PrefField f : PREFS) {
            if (f.pref().name().equals(key)) {
                final String problem;
                if (f.pref() == QPref.UNLOCK_DISTANCE_MULTIPLIER || f.pref() == QPref.WILD_OPPONENTS_MULTIPLIER) {
                    problem = Doubles.tryParse(value) == null ? l.getMessage("lblEnteraDecimal") : null;
                } else {
                    final Integer number = Ints.tryParse(value);
                    problem = number == null ? l.getMessage("lblEnteraNumber") : prefs.validatePreference(f.pref(), number);
                }
                if (problem == null) {
                    prefs.setPref(f.pref(), value);
                    prefs.save();
                }
                return problem;
            }
        }
        return null;
    }

    @Override
    public void resetPrefs() {
    }

    @Override
    public void handle(final BrowserChannel channel, final JsonObject msg, final String save, final Host host) {
        if (save == null) {
            shelf(channel, msg, host);
            return;
        }
        if (quest().getAssets() == null || deckCommand(channel, msg, host)) {
            return;
        }
        switch (msg.get("t").getAsString()) {
            case "questDuel" -> {
                duel(Wire.decode(msg, FromBrowser.QuestDuel.class).index(), host);
                return;
            }
            case "questChallenge" -> {
                final String id = Wire.decode(msg, FromBrowser.QuestChallenge.class).id();
                if (id != null && quest().getAchievements().getCurrentChallenges().contains(id)) {
                    fight(quest().getChallenges().get(id), host);
                }
                return;
            }
            case "questEnter", "questToken", "questTournamentStart", "questTournamentNext", "questTournamentLeave" -> {
                final String problem = switch (msg.get("t").getAsString()) {
                    case "questEnter" -> enter(Wire.decode(msg, FromBrowser.QuestEnter.class).title(), host);
                    case "questTournamentStart" -> startTournament();
                    case "questTournamentNext" -> nextMatch(host);
                    default -> {
                        if ("questToken".equals(msg.get("t").getAsString())) {
                            spendToken();
                        } else {
                            endTournament();
                        }
                        yield null;
                    }
                };
                if (problem != null) {
                    channel.send(new Notice(problem, null, true));
                }
                channel.send(bar());
                channel.send(tournamentsPage());
                if (reward != null && "questTournamentLeave".equals(msg.get("t").getAsString())) {
                    channel.send(reward);
                }
                return;
            }
            case "questTravel", "questUnlock" -> {
                if ("questTravel".equals(msg.get("t").getAsString())) {
                    QuestUtil.travelWorld();
                } else if (canUnlock()) {
                    QuestUtil.chooseAndUnlockEdition();
                }
                // A new world has new opponents, and either may change the shop, the bar and every list
                duels = null;
                page().forEach(channel::send);
                return;
            }
            case "questTournamentDeck" -> {
                final DeckGroup decks = quest().getDraftDecks().get(QuestEventDraft.DECK_NAME);
                if (decks != null) {
                    host.decks().openPool(decks.getHumanDeck(), quest().getDraftDecks(), GameType.QuestDraft, channel);
                }
                return;
            }
            case "questStall" -> {
                channel.send(bazaar(Wire.decode(msg, FromBrowser.QuestStall.class).name()));
                return;
            }
            case "questBuy" -> {
                final FromBrowser.QuestBuy buy = Wire.decode(msg, FromBrowser.QuestBuy.class);
                final String problem = buy(buy.stall(), buy.item());
                if (problem != null) {
                    channel.send(new Notice(problem, null, false));
                }
                // A pet, a charm or the zeppelin changes what the other pages offer
                channel.send(bar());
                channel.send(duelsPage());
                channel.send(challengesPage());
                channel.send(bazaar(buy.stall()));
                return;
            }
            case "questZeppelin" -> {
                // Level 2 is the zeppelin flown since the last match, which the end of a match sets back to 1
                if (quest().getAssets().hasItem(QuestItemType.ZEPPELIN) && quest().getAssets().getItemLevel(QuestItemType.ZEPPELIN) != 2) {
                    quest().getAchievements().setCurrentChallenges(null);
                    quest().getAssets().setItemLevel(QuestItemType.ZEPPELIN, 2);
                }
                channel.send(challengesPage());
                return;
            }
            case "questPet" -> {
                final FromBrowser.QuestPet pet = Wire.decode(msg, FromBrowser.QuestPet.class);
                // The plant's slot takes only the plant, as desktop's switch sets it; a pet must be one the quest owns
                final boolean owned = pet.name() == null || quest().getPetsStorage().getAvaliablePets(pet.slot(), quest().getAssets())
                        .stream().anyMatch(p -> p.getName().equals(pet.name()));
                if (pet.slot() >= 0 && pet.slot() < QuestController.MAX_PET_SLOTS && owned) {
                    quest().selectPet(pet.slot(), pet.name());
                    quest().save();
                }
            }
            case "questMatchLength" -> {
                final int games = Wire.decode(msg, FromBrowser.QuestMatchLength.class).games();
                if (quest().getMode() == QuestMode.Fantasy && matchLengths().contains(games)) {
                    quest().setMatchLength(String.valueOf(games));
                    quest().save();
                }
            }
            default -> {
                return;
            }
        }
        // Sent after a refusal too, so the page's control shows the choice that stands
        channel.send(duelsPage());
    }
}
