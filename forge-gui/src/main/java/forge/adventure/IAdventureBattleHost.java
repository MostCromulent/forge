package forge.adventure;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.item.PaperToken;
import forge.model.FModel;
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

/** The files through which adventure hands a battle to the web server it started, and through which the server sends back the result. */
public class IAdventureBattleHost {

    private static final String IPC_SUBDIR = "forge_adventure_ipc";
    private static final String BATTLE_REQUEST_FILE = "battle_request.dat";
    private static final String BATTLE_RESULT_FILE = "battle_result.dat";
    private static final String BATTLE_PENDING_SIGNAL = "battle_pending";
    private static final String BATTLE_COMPLETE_SIGNAL = "battle_complete";
    private static final String HUMAN_DECK_FILE = "human_deck.dck";
    private static final String AI_DECK_PREFIX = "ai_deck_";

    /** Names the folder for the web server that adventure starts. */
    public static final String IPC_DIR_ENV = "FORGE_ADVENTURE_IPC_DIR";

    // Adventure sets it with setIpcDir. The web server reads it from the environment adventure gave it
    private static volatile String ipcDir = System.getenv(IPC_DIR_ENV);

    /** A new folder for one launch, so files left by an earlier launch are never read. */
    public static String newLaunchDir() {
        return System.getProperty("java.io.tmpdir") + File.separator + IPC_SUBDIR + File.separator
                + "launch_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
    }

    public static void setIpcDir(String dir) {
        ipcDir = dir;
    }

    public static String getIpcDir() {
        return ipcDir;
    }

    private static Path getBattleRequestPath() {
        return Paths.get(ipcDir, BATTLE_REQUEST_FILE);
    }

    private static Path getBattleResultPath() {
        return Paths.get(ipcDir, BATTLE_RESULT_FILE);
    }

    private static Path getBattlePendingSignalPath() {
        return Paths.get(ipcDir, BATTLE_PENDING_SIGNAL);
    }

    private static Path getBattleCompleteSignalPath() {
        return Paths.get(ipcDir, BATTLE_COMPLETE_SIGNAL);
    }

    private static Path getHumanDeckPath() {
        return Paths.get(ipcDir, HUMAN_DECK_FILE);
    }

    private static Path getAiDeckPath(int index) {
        return Paths.get(ipcDir, AI_DECK_PREFIX + index + ".dck");
    }

    private static void ensureIpcDir() {
        try {
            Files.createDirectories(Paths.get(ipcDir));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void purgeIpcDir() {
        FileUtil.deleteDirectory(Paths.get(ipcDir).toFile());
    }

    // A signal left over from a prior battle is fine, its existence is all that matters
    private static void touch(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (FileAlreadyExistsException ignored) {
        }
    }

    // Written to a temp sibling and moved into place, so a reader never sees a half-written object
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

    public static void saveHumanDeck(Deck deck) {
        ensureIpcDir();
        DeckSerializer.writeDeck(deck, getHumanDeckPath().toFile());
    }

    public static void saveAiDeck(Deck deck, int index) {
        ensureIpcDir();
        DeckSerializer.writeDeck(deck, getAiDeckPath(index).toFile());
    }

    public static Deck loadHumanDeck() {
        return DeckSerializer.fromFile(getHumanDeckPath().toFile());
    }

    public static Deck loadAiDeck(int index) {
        return DeckSerializer.fromFile(getAiDeckPath(index).toFile());
    }

    /** Turns card names back into cards or tokens, as adventure's EffectData does. */
    public static List<IPaperCard> resolveCards(List<String> names) {
        List<IPaperCard> cards = new ArrayList<>();
        if (names == null) {
            return cards;
        }
        for (String name : names) {
            PaperCard card = FModel.getMagicDb().getCommonCards().getCard(name);
            if (card != null) {
                cards.add(card);
                continue;
            }
            try {
                PaperToken token = FModel.getMagicDb().getAllTokens().getToken(name);
                if (token != null) {
                    cards.add(token);
                } else {
                    System.err.println("Adventure battle: cannot find card/token \"" + name + "\"");
                }
            } catch (Exception e) {
                System.err.println("Adventure battle: cannot find card/token \"" + name + "\"");
            }
        }
        return cards;
    }

    public static class BattleRequest implements Serializable {
        private static final long serialVersionUID = 2L;

        public int humanStartingLife;
        public int humanManaShards;
        public int humanStartingHand;
        public boolean humanEnableETBCountersEffect;
        // Cards that adventure effects such as items and blessings start in play
        public List<String> humanExtraCardsOnBattlefield = new ArrayList<>();
        public List<String> humanExtraCardsInCommandZone = new ArrayList<>();

        // Its size is also the number of AI deck files
        public List<AIPlayerData> aiPlayers = new ArrayList<>();

        public String gameType = "Adventure";
        // Differs from gameType in Commander mode, whose battles keep Adventure's rules
        public String rulesType = "Adventure";
        public int gamesPerMatch = 1;
        public boolean playForAnte = false;
        public boolean matchAnteRarity = false;
        public boolean anteIncludeBasicLands = false;
        public boolean assignConspiracies = false;

        public void write() throws IOException {
            writeObjectAtomic(getBattleRequestPath(), this);
            touch(getBattlePendingSignalPath());
        }

        public static BattleRequest read() throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(getBattleRequestPath().toFile()))) {
                return (BattleRequest) ois.readObject();
            }
        }
    }

    public static class AIPlayerData implements Serializable {
        private static final long serialVersionUID = 2L;

        public String name;
        public int deckIndex;
        public int startingLife;
        public int teamNumber;
        public String aiType;  // one of "Default", "Reckless", "Cautious", "Experimental", or ""
        public int startingHand;
        public int manaShards;
        public boolean enableETBCountersEffect;
        // Cards an effect starts the enemy with, such as the monks' starting permanents
        public List<String> extraCardsOnBattlefield = new ArrayList<>();
        public List<String> extraCardsInCommandZone = new ArrayList<>();
    }

    public static class BattleResult implements Serializable {
        private static final long serialVersionUID = 1L;

        public boolean humanWon;
        // Negative when no game decided the battle. Adventure then keeps the shards it had
        public int shardsAfterBattle;

        public void write() throws IOException {
            writeObjectAtomic(getBattleResultPath(), this);
            Files.deleteIfExists(getBattlePendingSignalPath());
            touch(getBattleCompleteSignalPath());
        }

        public static BattleResult read() throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(getBattleResultPath().toFile()))) {
                return (BattleResult) ois.readObject();
            }
        }
    }

    public static boolean isBattlePending() {
        return Files.exists(getBattlePendingSignalPath());
    }

    public static boolean isBattleComplete() {
        return Files.exists(getBattleCompleteSignalPath());
    }

    /** Deletes the result and its signal once adventure has read them. */
    public static void clearBattleCompleteSignal() {
        try {
            Files.deleteIfExists(getBattleCompleteSignalPath());
            Files.deleteIfExists(getBattleResultPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
