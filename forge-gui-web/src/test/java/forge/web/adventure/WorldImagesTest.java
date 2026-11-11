package forge.web.adventure;

import com.badlogic.gdx.graphics.Pixmap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.adventure.world.World;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class WorldImagesTest {
    private World world;

    @BeforeClass
    public void makeWorld() {
        world = AdventureFixture.newWorld(42).getWorld();
        WorldImages.reset();
    }

    private static Pixmap decode(final byte[] png) {
        return new Pixmap(png, 0, png.length);
    }

    // Fails if a chunk's tile is placed or drawn differently from WorldBackground.buildChunkTexture
    @Test
    public void chunkMatchesBiomeSprite() {
        final Pixmap chunk = decode(WorldImages.chunk(1, 1));
        assertEquals(chunk.getWidth(), 480);
        final Pixmap tile = AdventureRuntime.onUi(() -> {
            final Pixmap drawn = world.getBiomeSprite(33, 34);
            final Pixmap copy = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
            copy.setBlending(Pixmap.Blending.None);
            copy.drawPixmap(drawn, 0, 0);
            return copy;
        });
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                assertEquals(chunk.getPixel(48 + x, 400 + y), tile.getPixel(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    public void chunkOutOfRangeIsNull() {
        assertNull(WorldImages.chunk(-1, 0));
        assertNull(WorldImages.chunk(world.getWidthInChunks() + 1, 0));
    }

    // Fails if the grid's rows are not flipped as World.getBiome flips them, or collision is not in green
    @Test
    public void gridMarksCollision() {
        final Pixmap grid = decode(WorldImages.grid());
        assertEquals(grid.getWidth(), world.getWidthInTiles());
        int found = 0;
        for (int x = 0; x < world.getWidthInTiles() && found < 20; x += 7) {
            for (int y = 0; y < world.getHeightInTiles() && found < 20; y += 7) {
                final int rgba = grid.getPixel(x, world.getHeightInTiles() - 1 - y);
                assertEquals((rgba >>> 16) & 0xff, world.isColliding(x, y) ? 255 : 0, "tile " + x + "," + y);
                assertEquals(rgba >>> 24, World.highestBiome(world.getBiome(x, y)), "tile " + x + "," + y);
                if (world.isColliding(x, y)) {
                    found++;
                }
            }
        }
        assertTrue(found > 0);
    }

    @Test
    public void atlasRejectsEscape() {
        assertNull(WorldImages.atlas("../../x.atlas"));
        assertNull(WorldImages.res("../config.json"));
    }

    @Test
    public void atlasFramesForAvatar() {
        final byte[] json = WorldImages.atlas("sprites/heroes/avatar.atlas");
        assertNotNull(json);
        final JsonObject atlas = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(atlas.get("image").getAsString().endsWith("avatar.png"));
        assertTrue(atlas.getAsJsonArray("frames").toString().contains("\"Kor_m\""));
        assertNotNull(WorldImages.res(atlas.get("image").getAsString()));
    }

    // Fails if a chunk's trees and rocks are not listed with the atlas region MapSprite.getMapSprites would draw
    @Test
    public void decorationsOfAChunk() {
        int cx = 0;
        int cy = 0;
        search:
        for (cx = 0; cx < world.getWidthInChunks(); cx++) {
            for (cy = 0; cy < world.getHeightInChunks(); cy++) {
                if (!world.GetMapObjects(cx, cy).isEmpty()) {
                    break search;
                }
            }
        }
        final int count = world.GetMapObjects(cx, cy).size();
        final var list = JsonParser.parseString(new String(WorldImages.decorations(cx, cy), StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(list.size(), count);
        final JsonObject first = list.get(0).getAsJsonObject();
        assertTrue(first.get("atlas").getAsString().endsWith(".atlas"));
        assertTrue(first.get("w").getAsInt() > 0);
        assertNull(WorldImages.decorations(-1, 0));
    }

    @Test
    public void keyChangesWithWorld() {
        final String first = WorldImages.key();
        AdventureFixture.newWorld(43);
        WorldImages.reset();
        assertTrue(!first.equals(WorldImages.key()));
        world = AdventureFixture.newWorld(42).getWorld();
        WorldImages.reset();
        assertEquals(WorldImages.key(), first);
    }
}
