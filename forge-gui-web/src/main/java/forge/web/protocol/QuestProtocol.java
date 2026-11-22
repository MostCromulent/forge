package forge.web.protocol;

import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;

import java.util.List;

/** Quest's messages in both directions. */
public final class QuestProtocol {
    private QuestProtocol() {
    }

    /** Chooses a quest pet for a slot. The plant uses slot 0. A null name chooses no pet. */
    @Command("questPet")
    public record QuestPet(int slot, @Nullable String name) {
    }

    @Command("questMatchLength")
    public record QuestMatchLength(int games) {
    }

    public enum QuestDeckAction { questDeckCurrent, questDeckView, questDeckEdit, questDeckDelete }

    /** An action on a quest deck, by name: make it the deck duels use, ask for its list, open it in the editor, or delete it. */
    @Command
    public record QuestDeckCommand(QuestDeckAction t, String deck) {
    }

    /** Makes an empty quest deck with the given name and opens it in the editor. */
    @Command("questDeckNew")
    public record QuestDeckNew(String name) {
    }

    @Command("questDeckRename")
    public record QuestDeckRename(String deck, String to) {
    }

    @Command("questOptions")
    public record QuestOptionsQuery() {
    }

    /** A new quest, as in NewQuestRules.Choices. Enums are sent by name, and the distribution's colours as WUBRGC letters. */
    @Command("questCreate")
    public record QuestCreate(String name, int difficulty, boolean fantasy, boolean commander, String world, String pool, @Nullable String format,
            @Nullable String precon, @Nullable String savedDeck, String poolType, String colors, boolean artifacts, boolean completeSet,
            boolean duplicates, int boosters, @Nullable String prizes, @Nullable String prizeFormat, boolean allowUnlocks) {
    }

    /** Opens a stall of the bazaar. With no name, it opens the first stall. */
    @Command("questStall")
    public record QuestStall(@Nullable String name) {
    }

    @Command("questBuy")
    public record QuestBuy(String stall, String item) {
    }

    @Command("questChallenge")
    public record QuestChallenge(String id) {
    }

    /** Flies the zeppelin. The challenges are drawn again. This is allowed once between two matches. */
    @Command("questZeppelin")
    public record QuestZeppelin() {
    }

    /** Enters a tournament on offer, by its title. This pays its fee and opens its draft. */
    @Command("questEnter")
    public record QuestEnter(String title) {
    }

    /** Spends a draft token on a tournament of a format the host asks for. */
    @Command("questToken")
    public record QuestToken() {
    }

    /** Starts the entered tournament once its deck is built. */
    @Command("questTournamentStart")
    public record QuestTournamentStart() {
    }

    /** Plays the player's next tournament match. The AI matches before it are decided first. */
    @Command("questTournamentNext")
    public record QuestTournamentNext() {
    }

    /** Opens the tournament's deck in the editor. */
    @Command("questTournamentDeck")
    public record QuestTournamentDeck() {
    }

    /** Leaves the entered tournament. If it has started, its prizes are collected. */
    @Command("questTournamentLeave")
    public record QuestTournamentLeave() {
    }

    /** Travels to another world. The host asks which one. */
    @Command("questTravel")
    public record QuestTravel() {
    }

    /** Unlocks a set for the quest's format. The host asks which one. */
    @Command("questUnlock")
    public record QuestUnlock() {
    }

    /** Fights a duel from the Duels page, by its index in the list sent to that page. */
    @Command("questDuel")
    public record QuestDuel(int index) {
    }

    /** A saved quest: its mode, difficulty and rank, its record, cards and credits, its world, and when it was saved. */
    public record QuestSave(String name, String mode, String difficulty, String rank, int wins, int losses, int cards, long credits,
            String world, @Nullable String saved) {
    }

    /** The saved quests, and the one played last. */
    @Message("questSaves")
    public record QuestSaves(List<QuestSave> saves, @Nullable String current) {
    }

    /** A duel on offer: its index in the list, which the browser sends back, and a card from the opponent's deck to show as its picture. */
    public record QuestDuelRow(int index, String title, int difficulty, String description, String colors, @Nullable String face, boolean random) {
    }

    /** A pet slot's choice: the owned pets by name, and the one chosen, or null for none. */
    public record QuestPetChoice(int slot, List<String> pets, @Nullable String chosen) {
    }

    /** The Duels page: the duels, the player's record, the line about the next challenge, the pets and the match lengths the charms allow. */
    @Message("questDuels")
    public record QuestDuels(List<QuestDuelRow> duels, int wins, int losses, int streak, int bestStreak, @Nullable String nextChallenge,
            List<QuestPetChoice> pets, List<Integer> matchLengths, int matchLength, String deck, @Nullable String deckProblem, boolean canUnlock) {
    }

    /** A quest deck: its picture, colours and size, why it cannot be played if it cannot, and whether duels use it. */
    public record QuestDeckRow(String name, @Nullable String image, String colors, int size, int sideboard, @Nullable String problem, boolean current) {
    }

    @Message("questDecks")
    public record QuestDecks(List<QuestDeckRow> decks) {
    }

    /** A challenge on offer: its terms as set in its file, the cards each side starts with in play, and whether the challenge supplies the player's deck. */
    public record QuestChallengeRow(String id, String title, int difficulty, String description, @Nullable String face, int aiLife, @Nullable Integer humanLife,
            int credits, List<String> cardReward, boolean repeatable, List<String> humanCards, List<String> aiCards, boolean fixedDeck) {
    }

    /** The Challenges page: the challenges, how many are open out of the number the wins allow, the line about the next one, and the zeppelin. */
    @Message("questChallenges")
    public record QuestChallenges(List<QuestChallengeRow> challenges, int open, int max, @Nullable String nextIn, boolean zeppelin, boolean zeppelinUsed) {
    }

    /** A bazaar stall: its name, which the browser sends back, the merchant's name, a line of text about it, and its skin icon. */
    public record QuestStallRow(String name, String displayName, String fluff, String icon) {
    }

    /** Something for sale at a stall: a skin icon or a card to show it, its price, its level and highest level, and for a pet, what it does now and what the next level gives. */
    public record QuestItemRow(String name, String description, @Nullable String icon, @Nullable String card, int price, int level, int maxLevel,
            @Nullable String now, @Nullable String next) {
    }

    /** The bazaar: its stalls, the one open, and what that stall sells. */
    @Message("questBazaar")
    public record QuestBazaar(List<QuestStallRow> stalls, @Nullable String stall, List<QuestItemRow> items) {
    }

    /** A tournament on offer: its title, which the browser sends back, the packs drafted by set name with a picture of each, and its fee. */
    public record QuestTournamentRow(String title, List<String> packs, List<String> packImages, int fee, boolean affordable) {
    }

    /** A place in the bracket. name is who holds it: null while undecided, empty for the player. you is true for the player. state is won (went on to the next round), out (lost there) or open (still to be played). */
    public record QuestSeat(@Nullable String name, boolean you, String state) {
    }

    /** The entered tournament. rounds go from the first eight seats to the winner. next is the player's next opponent while the player has a match left, or empty when not yet known. placing is the player's placing. drafted says whether its draft was finished into a pool. */
    public record QuestBracket(String title, List<List<QuestSeat>> rounds, @Nullable String next, String placing, boolean started, boolean drafted) {
    }

    /** The Tournaments page: the tournaments on offer while none is entered, the tokens to make one, the past placings from first to fourth, and the entered one. */
    @Message("questTournaments")
    public record QuestTournaments(List<QuestTournamentRow> offered, int tokens, List<Integer> placings, @Nullable QuestBracket bracket) {
    }

    /** A difficulty and what a quest started at it gets: credits, and the starting pool's commons, uncommons and rares. */
    public record QuestDifficultyRow(String name, int credits, int commons, int uncommons, int rares) {
    }

    /** What the new-quest form offers, each list by name. */
    @Message("questOptions")
    public record QuestOptions(List<String> worlds, List<String> formatWorlds, List<String> sanctioned, List<String> casual, List<String> precons,
            List<String> sealedDecks, List<String> draftDecks, List<String> cubes, List<QuestDifficultyRow> difficulties) {
    }
}
