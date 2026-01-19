package forge.adventure;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Interface and data classes for IPC-based Adventure battles.
 * Allows Adventure mode (running in a separate process) to trigger battles
 * that are hosted by the desktop GUI with its native Swing UI.
 */
public class IAdventureBattleHost {

    // File paths for IPC
    private static final String IPC_DIR = System.getProperty("java.io.tmpdir") + File.separator + "forge_adventure_ipc";
    private static final String BATTLE_REQUEST_FILE = "battle_request.dat";
    private static final String BATTLE_RESULT_FILE = "battle_result.dat";
    private static final String BATTLE_PENDING_SIGNAL = "battle_pending";
    private static final String BATTLE_COMPLETE_SIGNAL = "battle_complete";
    private static final String HUMAN_DECK_FILE = "human_deck.dck";
    private static final String AI_DECK_PREFIX = "ai_deck_";

    public static String getIpcDir() {
        return IPC_DIR;
    }

    public static Path getBattleRequestPath() {
        return Paths.get(IPC_DIR, BATTLE_REQUEST_FILE);
    }

    public static Path getBattleResultPath() {
        return Paths.get(IPC_DIR, BATTLE_RESULT_FILE);
    }

    public static Path getBattlePendingSignalPath() {
        return Paths.get(IPC_DIR, BATTLE_PENDING_SIGNAL);
    }

    public static Path getBattleCompleteSignalPath() {
        return Paths.get(IPC_DIR, BATTLE_COMPLETE_SIGNAL);
    }

    /**
     * Ensures the IPC directory exists.
     */
    public static void ensureIpcDir() {
        try {
            Files.createDirectories(Paths.get(IPC_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Cleans up all IPC files.
     */
    public static void cleanupIpcFiles() {
        try {
            Files.deleteIfExists(getBattleRequestPath());
            Files.deleteIfExists(getBattleResultPath());
            Files.deleteIfExists(getBattlePendingSignalPath());
            Files.deleteIfExists(getBattleCompleteSignalPath());
            // Clean up deck files
            Files.deleteIfExists(getHumanDeckPath());
            for (int i = 0; i < 8; i++) {
                Files.deleteIfExists(getAiDeckPath(i));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Path getHumanDeckPath() {
        return Paths.get(IPC_DIR, HUMAN_DECK_FILE);
    }

    public static Path getAiDeckPath(int index) {
        return Paths.get(IPC_DIR, AI_DECK_PREFIX + index + ".dck");
    }

    /**
     * Saves a deck to the IPC directory.
     */
    public static void saveHumanDeck(Deck deck) {
        ensureIpcDir();
        DeckSerializer.writeDeck(deck, getHumanDeckPath().toFile());
    }

    /**
     * Saves an AI deck to the IPC directory.
     */
    public static void saveAiDeck(Deck deck, int index) {
        ensureIpcDir();
        DeckSerializer.writeDeck(deck, getAiDeckPath(index).toFile());
    }

    /**
     * Loads the human deck from the IPC directory.
     */
    public static Deck loadHumanDeck() {
        return DeckSerializer.fromFile(getHumanDeckPath().toFile());
    }

    /**
     * Loads an AI deck from the IPC directory.
     */
    public static Deck loadAiDeck(int index) {
        return DeckSerializer.fromFile(getAiDeckPath(index).toFile());
    }

    /**
     * Data class representing a battle request from Adventure mode.
     */
    public static class BattleRequest implements Serializable {
        private static final long serialVersionUID = 2L;

        // Human player data
        public String humanPlayerName;
        public int humanStartingLife;
        public int humanManaShards;
        public int humanAvatarIndex;

        // AI player(s) data - count indicates how many AI decks to load
        public List<AIPlayerData> aiPlayers = new ArrayList<>();

        // Game settings
        public String gameType = "Adventure";  // GameType name
        public int gamesPerMatch = 1;
        public boolean playForAnte = false;
        public boolean matchAnteRarity = false;
        public boolean isBossBattle = false;

        // Enemy info for display
        public String enemyName;

        /**
         * Writes this request to the IPC file.
         */
        public void write() throws IOException {
            ensureIpcDir();
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(getBattleRequestPath().toFile()))) {
                oos.writeObject(this);
            }
            // Create pending signal
            Files.createFile(getBattlePendingSignalPath());
        }

        /**
         * Reads a request from the IPC file.
         */
        public static BattleRequest read() throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(getBattleRequestPath().toFile()))) {
                return (BattleRequest) ois.readObject();
            }
        }
    }

    /**
     * Data class for AI player in a battle.
     */
    public static class AIPlayerData implements Serializable {
        private static final long serialVersionUID = 2L;

        public String name;
        public int deckIndex;  // Index for loadAiDeck()
        public int startingLife;
        public int teamNumber;
        public String aiType;  // "Default", "Reckless", "Cautious", "Experimental", or ""
    }

    /**
     * Data class representing the result of a battle.
     */
    public static class BattleResult implements Serializable {
        private static final long serialVersionUID = 1L;

        public boolean humanWon;
        public int shardsAfterBattle;

        // Ante results (if ante was enabled)
        public List<String> cardsWon = new ArrayList<>();  // Card names
        public List<String> cardsLost = new ArrayList<>();  // Card names

        /**
         * Writes this result to the IPC file.
         */
        public void write() throws IOException {
            ensureIpcDir();
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(getBattleResultPath().toFile()))) {
                oos.writeObject(this);
            }
            // Delete pending signal, create complete signal
            Files.deleteIfExists(getBattlePendingSignalPath());
            Files.createFile(getBattleCompleteSignalPath());
        }

        /**
         * Reads a result from the IPC file.
         */
        public static BattleResult read() throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(getBattleResultPath().toFile()))) {
                return (BattleResult) ois.readObject();
            }
        }
    }

    /**
     * Checks if a battle request is pending.
     */
    public static boolean isBattlePending() {
        return Files.exists(getBattlePendingSignalPath());
    }

    /**
     * Checks if a battle is complete.
     */
    public static boolean isBattleComplete() {
        return Files.exists(getBattleCompleteSignalPath());
    }

    /**
     * Clears the battle complete signal (called by Adventure after reading result).
     */
    public static void clearBattleCompleteSignal() {
        try {
            Files.deleteIfExists(getBattleCompleteSignalPath());
            Files.deleteIfExists(getBattleResultPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
