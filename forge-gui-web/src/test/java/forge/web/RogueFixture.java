package forge.web;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.RogueMatch;
import forge.gamemodes.rogue.path.NodeBazaar;
import forge.gamemodes.rogue.path.NodeChest;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.gamemodes.rogue.path.RoguePath;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.player.GamePlayerUtil;
import forge.util.FileUtil;
import forge.util.MyRandom;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/** Makes Rogue Commander saves in the test profile. Tests call {@link WebTestSupport#initModel} first. */
public final class RogueFixture {
    private RogueFixture() {
    }

    /** Outside the test profile this directory holds the developer's own run. */
    private static Path saveDir() {
        final Path dir = Path.of(RogueIO.ROGUE_SAVE_DIR).toAbsolutePath().normalize();
        if (!dir.toString().replace(File.separatorChar, '/').contains("/target/test-home/")) {
            throw new IllegalStateException("not the test profile: " + dir);
        }
        return dir;
    }

    /** Writes a resource into the save directory, gzipped as Rogue Commander writes it. */
    public static Path install(final String resource, final String fileName) {
        final Path file = saveDir().resolve(fileName);
        try (InputStream in = RogueFixture.class.getResourceAsStream(resource)) {
            Files.createDirectories(file.getParent());
            try (OutputStream zip = new GZIPOutputStream(Files.newOutputStream(file))) {
                in.transferTo(zip);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** Empties the save directory and drops the progress in memory. */
    public static void clean() {
        final Path dir = saveDir();
        try {
            if (Files.exists(dir) && !FileUtil.deleteDirectory(dir.toFile())) {
                throw new IOException("could not empty " + dir);
            }
            Files.createDirectories(dir);
            final Field instance = RogueMetaProgress.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (final IOException | ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A saved run of the Aragorn deck with 14 of 23 life and 8 gold. Rows 0 to 2 each hold a plane, a sanctum, a bazaar, an event and a chest, in that column order, and row 3 holds the boss.
     * The current node is the one of the given type in row 1. The one above it in row 0 is completed, so no other node in row 1 is reachable.
     */
    public static RogueRun run(final Class<? extends RoguePathNode> at) {
        saveDir();
        MyRandom.setRandom(new Random(1));
        final List<RoguePathNode> nodes = new ArrayList<>();
        // Two normal planes, then an elite
        for (final String deck : new String[] {"anikthea", "rafiq", "urza"}) {
            final int row = nodes.size() / 5;
            add(nodes, new NodePlanebound(planebound(deck)), row, 0);
            add(nodes, new NodeSanctum(), row, 1);
            add(nodes, new NodeBazaar(), row, 2);
            add(nodes, new NodeEvent(RogueEvent.WANDERING_HEALER), row, 3);
            add(nodes, new NodeChest(List.of(ChestEffect.BANNER_OF_CHAMPIONS, ChestEffect.CARD_CACHE_GAMECHANGER)), row, 4);
        }
        add(nodes, new NodePlanebound(planebound("korvold")), 3, 0);

        final RogueDeck aragorn = RogueConfig.loadRogueDecks().stream()
                .filter(d -> "Aragorn, the Uniter".equals(d.getCommanderCardName())).findFirst().orElseThrow();
        final RogueRun run = new RogueRun(aragorn);
        run.setPath(new RoguePath(nodes));
        for (int i = 5; i < 10; i++) {
            if (at.isInstance(nodes.get(i))) {
                run.setCurrentNodeIndex(i);
                nodes.get(i - 5).setCompleted(true);
            }
        }
        run.initializeMaxLife(23);
        run.setCurrentLife(14);
        run.setCurrentGold(8);
        run.setName("Aragorn_1");
        RogueIO.saveRun(run);
        return run;
    }

    /** The match's game, over by a concession before it starts. Starting life is the life it ends with. */
    static GameView finished(final RogueMatch match, final boolean humanWins) {
        final GameRules rules = new GameRules(GameType.RogueCommander);
        rules.setAppliedVariants(match.variants());
        final Game game = new Match(rules, match.players(), "Rogue").createGame();
        for (final Player player : game.getRegisteredPlayers()) {
            final boolean human = player.getLobbyPlayer() == GamePlayerUtil.getGuiPlayer();
            if (human) {
                // Zones are only filled when a game starts
                match.run().getCarryCards().forEach(c ->
                        player.getZone(ZoneType.Command).add(Card.fromPaperCard(c.toPaperCard(), player)));
            }
            if (human != humanWins) {
                player.concede();
            }
        }
        game.setGameOver(GameEndReason.AllOpponentsLost);
        return game.getView();
    }

    private static void add(final List<RoguePathNode> nodes, final RoguePathNode node, final int row, final int column) {
        node.setRowIndex(row);
        node.setColumnIndex(column);
        nodes.add(node);
    }

    private static RoguePlanebound planebound(final String deck) {
        return RogueConfig.loadPlanebounds().stream()
                .filter(p -> p.deckPath().endsWith("/" + deck + ".dck")).findFirst().orElseThrow();
    }
}
