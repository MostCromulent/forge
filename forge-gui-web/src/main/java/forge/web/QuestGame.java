package forge.web;

import com.google.gson.JsonObject;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.NewQuestRules;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEvent;
import forge.gamemodes.quest.QuestEventChallenge;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestSpellShop;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.QuestWinLoseController;
import forge.gamemodes.quest.StartingPoolPreferences.PoolType;
import forge.gamemodes.quest.StartingPoolPreferences;
import forge.gamemodes.quest.StartingPoolType;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.bazaar.QuestPetController;
import forge.gamemodes.quest.data.DeckConstructionRules;
import forge.gamemodes.quest.data.QuestAchievements;
import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.DifficultyPrefs;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.itemmanager.SItemManagerUtil;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        final List<String> casual = new ArrayList<>();
        FModel.getFormats().getArchivedList().forEach(f -> casual.add(f.getName()));
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
        return new QuestOptions(worlds, sanctioned, casual, NewQuestRules.startingPrecons(), sealed, draft, cubes, difficulties);
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
        return List.of(bar(), duelsPage(), decksPage(), challengesPage());
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
                pets, lengths, quest.getMatchLength(), deck == null ? "" : deck.getName(), problem);
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
        // Fantasy mode's only, as desktop shows challenges
        if (quest.getMode() == QuestMode.Fantasy) {
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
        }
        final int max = Math.max(0, Math.min(5, record.getWin() / quest.getTurnsToUnlockChallenge() - record.getChallengesPlayed()));
        final int wins = QuestUtil.nextChallengeInWins();
        final Localizer text = Localizer.getInstance();
        final String next = rows.isEmpty() ? wins == 1 ? text.getMessage("lblnextChallengeInWins1")
                : text.getMessage("lblnextChallengeInWins2").replace("%n", String.valueOf(wins)) : null;
        return new QuestChallenges(rows, rows.size(), max, next, quest.getAssets().hasItem(QuestItemType.ZEPPELIN),
                quest.getAssets().getItemLevel(QuestItemType.ZEPPELIN) == 2);
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

    @Override
    public CampaignStats stats(final String scope) {
        return new CampaignStats("", List.of(), List.of(), List.of(), null, null);
    }

    @Override
    public CampaignPrefs prefs(final String problem) {
        return new CampaignPrefs("", List.of(), "", problem, false);
    }

    @Override
    public String setPref(final String key, final String value) {
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
