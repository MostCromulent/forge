package forge.adventure;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.util.FileUtil;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Interface and data classes for IPC-based Adventure battles.
 * Allows Adventure mode (running in a separate process) to trigger battles
 * that are hosted by the desktop GUI with its native Swing UI.
 */
public class IAdventureBattleHost {

    private static final String IPC_SUBDIR = "forge_adventure_ipc";
    private static final String BATTLE_REQUEST_FILE = "battle_request.dat";
    private static final String BATTLE_RESULT_FILE = "battle_result.dat";
    private static final String BATTLE_PENDING_SIGNAL = "battle_pending";
    private static final String BATTLE_COMPLETE_SIGNAL = "battle_complete";
    private static final String HUMAN_DECK_FILE = "human_deck.dck";
    private static final String AI_DECK_PREFIX = "ai_deck_";

    /** Env var the desktop launcher uses to hand its private per-launch IPC directory to the spawned Adventure process. */
    public static final String IPC_DIR_ENV = "FORGE_ADVENTURE_IPC_DIR";

    // Resolved once from the launcher-supplied env var (spawned process) or the default temp path (standalone).
    // The launcher overrides it in its own process via setIpcDir so both sides agree on a private directory.
    private static volatile String ipcDir = resolveInitialDir();

    private static String defaultBaseDir() {
        return System.getProperty("java.io.tmpdir") + File.separator + IPC_SUBDIR;
    }

    private static String resolveInitialDir() {
        String fromEnv = System.getenv(IPC_DIR_ENV);
        return (fromEnv != null && !fromEnv.isEmpty()) ? fromEnv : defaultBaseDir();
    }

    /** A fresh, unique IPC directory for one launch, so stale files from a prior or concurrent session can't bleed in. */
    public static String newLaunchDir() {
        return defaultBaseDir() + File.separator + "launch_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
    }

    /** Points both the launcher and its spawned process at the same private per-launch directory. */
    public static void setIpcDir(String dir) {
        if (dir != null && !dir.isEmpty()) {
            ipcDir = dir;
        }
    }

    public static String getIpcDir() {
        return ipcDir;
    }

    public static Path getBattleRequestPath() {
        return Paths.get(ipcDir, BATTLE_REQUEST_FILE);
    }

    public static Path getBattleResultPath() {
        return Paths.get(ipcDir, BATTLE_RESULT_FILE);
    }

    public static Path getBattlePendingSignalPath() {
        return Paths.get(ipcDir, BATTLE_PENDING_SIGNAL);
    }

    public static Path getBattleCompleteSignalPath() {
        return Paths.get(ipcDir, BATTLE_COMPLETE_SIGNAL);
    }

    /**
     * Ensures the IPC directory exists.
     */
    public static void ensureIpcDir() {
        try {
            Files.createDirectories(Paths.get(ipcDir));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Removes the entire per-launch IPC directory tree. Called on launcher shutdown.
     */
    public static void purgeIpcDir() {
        FileUtil.deleteDirectory(Paths.get(ipcDir).toFile());
    }

    // Creates the file if absent; a signal left over from a prior battle is fine, its existence is all that matters.
    private static void touch(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (FileAlreadyExistsException ignored) {
        }
    }

    // Writes to a temp sibling then moves it onto the target so a reader never observes a half-written object,
    // and a stale target from a prior battle is replaced rather than colliding.
    private static void writeObjectAtomic(Path target, Serializable obj) throws IOException {
        ensureIpcDir();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(tmp))) {
            oos.writeObject(obj);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path getHumanDeckPath() {
        return Paths.get(ipcDir, HUMAN_DECK_FILE);
    }

    public static Path getAiDeckPath(int index) {
        return Paths.get(ipcDir, AI_DECK_PREFIX + index + ".dck");
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

        public String humanPlayerName;
        public int humanStartingLife;
        public int humanManaShards;
        public int humanAvatarIndex;

        // aiPlayers.size() also tells the desktop how many AI deck files to load
        public List<AIPlayerData> aiPlayers = new ArrayList<>();

        public String gameType = "Adventure";
        public int gamesPerMatch = 1;
        public boolean playForAnte = false;
        public boolean matchAnteRarity = false;
        public boolean isBossBattle = false;

        public String enemyName;

        /**
         * Writes this request to the IPC file.
         */
        public void write() throws IOException {
            writeObjectAtomic(getBattleRequestPath(), this);
            touch(getBattlePendingSignalPath());
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
        public int deckIndex;
        public int startingLife;
        public int teamNumber;
        public String aiType;  // one of "Default", "Reckless", "Cautious", "Experimental", or ""
    }

    /**
     * Data class representing the result of a battle.
     */
    public static class BattleResult implements Serializable {
        private static final long serialVersionUID = 1L;

        public boolean humanWon;
        public int shardsAfterBattle;

        public List<String> cardsWon = new ArrayList<>();
        public List<String> cardsLost = new ArrayList<>();

        /**
         * Writes this result to the IPC file.
         */
        public void write() throws IOException {
            writeObjectAtomic(getBattleResultPath(), this);
            Files.deleteIfExists(getBattlePendingSignalPath());
            touch(getBattleCompleteSignalPath());
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
