package forge.web.adventure;

import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.world.World;
import forge.web.protocol.CampaignProtocol.AdventureEnemy;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class OverworldTest {
    private World world;
    private Overworld overworld;
    private final List<String> heard = new ArrayList<>();
    /** The bottom-left tile of a 25 by 25 block where nothing blocks walking, in world pixels. */
    private float openX;
    private float openY;

    @BeforeMethod
    public void setUp() {
        world = AdventureFixture.newWorld(42).getWorld();
        heard.clear();
        overworld = AdventureRuntime.onUi(() -> new Overworld(world, new Random(7), new Overworld.Listener() {
            @Override
            public void spawned(final AdventureEnemy e) {
                heard.add("spawned " + e.id());
            }

            @Override
            public void despawned(final int id) {
                heard.add("despawned " + id);
            }

            @Override
            public void encounter(final int id, final EnemySprite mob) {
                heard.add("encounter " + id);
            }
        }));
        final int[] open = openBlock(25);
        openX = open[0] * world.getTileSize();
        openY = open[1] * world.getTileSize();
    }

    private int[] openBlock(final int size) {
        for (int x = 10; x < world.getWidthInTiles() - size; x += 2) {
            for (int y = 10; y < world.getHeightInTiles() - size; y += 2) {
                boolean clear = true;
                for (int i = 0; i < size && clear; i++) {
                    for (int j = 0; j < size && clear; j++) {
                        clear = !world.isColliding(x + i, y + j);
                    }
                }
                if (clear) {
                    return new int[] {x, y};
                }
            }
        }
        throw new AssertionError("no open block");
    }

    private static EnemyData enemy(final float speed, final boolean flying) {
        final EnemyData data = new EnemyData(WorldData.getEnemy("Jodah"));
        data.speed = speed;
        data.flying = flying;
        return data;
    }

    private AdventureEnemy place(final EnemyData data, final float x, final float y) {
        return AdventureRuntime.onUi(() -> overworld.place(data, x, y));
    }

    private void tick(final float delta) {
        AdventureRuntime.onUi(() -> {
            overworld.tick(delta);
            return null;
        });
    }

    private AdventureEnemy only() {
        return overworld.enemies().get(0);
    }

    // Fails if chase does not move by speed times delta toward the player, as WorldStage.onActing does
    @Test
    public void chaseMovesBySpeedTimesDelta() {
        overworld.player(openX + 100, openY + 200, true);
        place(enemy(30, false), openX + 200, openY + 200);
        tick(0.5f);
        assertEquals(only().x(), openX + 185, 0.01);
        assertEquals(only().y(), openY + 200, 0.01);
    }

    // Fails if the axis-split fallback is missing: a diagonal into a floor must keep the free axis.
    // The fallback checks the sprite's full height, as WorldStage.onActing does, so the free rows sit above the floor
    @Test
    public void chaseSlidesAlongWall() {
        final int tile = world.getTileSize();
        final int[] floor = floorBelow();
        final float x = floor[0] * tile;
        final float y = floor[1] * tile + 0.5f;
        overworld.player(x - 200, y - 300, true);
        place(enemy(40, false), x, y);
        tick(0.25f);
        assertTrue(only().x() < x);
        assertEquals(only().y(), y, 0.01);
    }

    /** A tile with four free rows from it upward for 8 tiles to the left and 2 to the right, over a row of colliding tiles. */
    private int[] floorBelow() {
        for (int x = 20; x < world.getWidthInTiles() - 20; x++) {
            for (int y = 20; y < world.getHeightInTiles() - 20; y++) {
                boolean shape = true;
                for (int i = -8; i <= 2 && shape; i++) {
                    shape = world.isColliding(x + i, y - 1);
                    for (int j = 0; j < 4 && shape; j++) {
                        shape = !world.isColliding(x + i, y + j);
                    }
                }
                if (shape) {
                    return new int[] {x, y};
                }
            }
        }
        throw new AssertionError("no floor found");
    }

    @Test
    public void flyingIgnoresCollision() {
        final int[] solid = solidTile();
        final float x = solid[0] * world.getTileSize() + 40;
        final float y = solid[1] * world.getTileSize();
        overworld.player(x - 200, y, true);
        place(enemy(40, true), x, y);
        tick(0.5f);
        assertEquals(only().x(), x - 20, 0.01);
    }

    private int[] solidTile() {
        for (int x = 20; x < world.getWidthInTiles() - 20; x++) {
            for (int y = 20; y < world.getHeightInTiles() - 20; y++) {
                if (world.isColliding(x, y) && world.isColliding(x - 1, y) && world.isColliding(x - 2, y)) {
                    return new int[] {x, y};
                }
            }
        }
        throw new AssertionError("no solid tile");
    }

    // Fails if lifetime runs while the player stands still; mobile only advances globalTimer while the player moves
    @Test
    public void lifetimeOnlyWhileMoving() {
        // Hidden, so nothing that spawns can catch the player and freeze the map
        overworld.modify("Hide", 1000);
        overworld.player(openX + 20, openY + 20, false);
        final AdventureEnemy e = place(enemy(0, false), openX + 600, openY + 600);
        for (int i = 0; i < 600; i++) {
            tick(0.05f);
        }
        assertEquals(overworld.enemies().size(), 1);
        overworld.player(openX + 20, openY + 20, true);
        for (int i = 0; i < 420; i++) {
            tick(0.05f);
        }
        assertTrue(heard.contains("despawned " + e.id()), heard.toString());
        assertTrue(overworld.enemies().stream().noneMatch(n -> n.id() == e.id()));
    }

    @Test
    public void encounterOnOverlap() {
        overworld.player(openX + 100, openY + 100, true);
        final AdventureEnemy e = place(enemy(0, false), openX + 102, openY + 100);
        tick(0.05f);
        tick(0.05f);
        assertEquals(heard.stream().filter(h -> h.startsWith("encounter")).count(), 1);
        assertTrue(heard.contains("encounter " + e.id()));
        assertTrue(overworld.frozen());
    }

    // Fails if an enemy touching the player on the tick a point of interest is entered starts a battle (Review Focus 4)
    @Test
    public void poiFirstSameTick() {
        overworld.player(openX + 100, openY + 100, true);
        place(enemy(0, false), openX + 102, openY + 100);
        overworld.enteredPoi();
        tick(0.05f);
        assertTrue(heard.stream().noneMatch(h -> h.startsWith("encounter")));
    }

    @Test
    public void hideStopsChase() {
        overworld.player(openX + 100, openY + 200, true);
        place(enemy(30, false), openX + 200, openY + 200);
        overworld.modify("Hide", 5);
        tick(0.5f);
        assertEquals(only().x(), openX + 200, 0.01);
    }

    @Test
    public void spawnsWhileMoving() {
        overworld.player(openX + 300, openY + 300, true);
        for (int i = 0; i < 200; i++) {
            tick(0.05f);
        }
        assertTrue(heard.stream().anyMatch(h -> h.startsWith("spawned")), heard.toString());
    }

    @Test
    public void saveLoadKeys() {
        overworld.player(openX + 20, openY + 20, true);
        place(enemy(10, false), openX + 300, openY + 310);
        tick(1f);
        final var data = overworld.save();
        final Overworld other = AdventureRuntime.onUi(() -> new Overworld(world, new Random(1), null));
        AdventureRuntime.onUi(() -> {
            other.load(data);
            return null;
        });
        assertEquals(other.enemies().size(), overworld.enemies().size());
        assertEquals(other.enemies().get(0).x(), only().x(), 0.01);
        assertEquals(other.save().readFloat("globalTimer"), data.readFloat("globalTimer"), 0.0001);
        assertNotEquals(data.readFloat("globalTimer"), 0f);
    }

    // Fails if the browser cannot learn that the player flies, so it keeps blocking the hero on walls
    @Test
    public void flyingIsReported() {
        overworld.player(openX + 20, openY + 20, true);
        assertFalse(overworld.flying());
        overworld.modify("Fly", 1);
        assertTrue(overworld.flying());
        tick(0.6f);
        tick(0.6f);
        assertFalse(overworld.flying());
    }
}
