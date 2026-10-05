package forge.web;

import com.google.gson.JsonObject;
import forge.card.CardType;
import forge.deck.Deck;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.bazaar.QuestPetController;
import forge.gamemodes.quest.data.QuestAchievements;
import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.game.GameView;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.FromBrowser.CatalogueQuery;
import forge.web.FromBrowser.TradePick;
import forge.web.ToBrowser.*;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Holds only what one session knows that the save does not, as the quest itself is Forge's, one for the whole process. */
final class QuestGame implements Campaign {
    private static final String[] DIFFICULTIES = {"questDifficultyEasy", "questDifficultyMedium", "questDifficultyHard", "questDifficultyExpert"};
    private List<QuestEventDuel> duels;

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

    /** Renaming waits for the shelf's menu, which comes with the new-quest form. */
    @Override
    public String rename(final String name, final String to) {
        return null;
    }

    @Override
    public void delete(final String name) {
    }

    @Override
    public List<Record> page() {
        return List.of(bar(), duelsPage());
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
        return null;
    }

    @Override
    public void claim(final Host host) {
    }

    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        return null;
    }

    @Override
    public CampaignResult result() {
        return null;
    }

    @Override
    public void left() {
    }

    // The pages that ask for these come in later parts, so they answer with nothing
    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        return new CataloguePage(q.request(), List.of(), 0, 0, 0, false, q.source());
    }

    @Override
    public Trading trading() {
        return new Trading(List.of(), "", null);
    }

    @Override
    public String trade(final String source, final List<TradePick> picks) {
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
        if (save == null || quest().getAssets() == null) {
            return;
        }
        switch (msg.get("t").getAsString()) {
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
