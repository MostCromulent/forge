package forge.web.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.adventure.util.Config;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pictures the browser draws the world map from, as PNG. They are drawn by the mobile client's own code, so the map looks the same.
 * A world never changes once made, so each picture is cached by the browser under the world's key. Every method is synchronized,
 * because World.getBiomeSprite draws into one shared image.
 */
public final class WorldImages {
    private static final int KEPT_CHUNKS = 64;
    private static final Map<Long, byte[]> chunks = new LinkedHashMap<>(KEPT_CHUNKS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, byte[]> eldest) {
            return size() > KEPT_CHUNKS;
        }
    };
    private static String key;
    private static Pixmap scratch;

    private WorldImages() {}

    private static World world() {
        return WorldSave.getCurrentSave().getWorld();
    }

    /** A save was opened or made, so the pictures of the last world are dropped. */
    public static synchronized void reset() {
        chunks.clear();
        key = null;
    }

    /** Names the open world in picture addresses. Saves share one World object, so its terrain tells two worlds apart. */
    public static synchronized String key() {
        if (key == null) {
            key = Integer.toHexString(Arrays.deepHashCode(world().terrainMap));
        }
        return key;
    }

    /** A chunk as WorldBackground.buildChunkTexture draws it, or null outside the world. */
    public static synchronized byte[] chunk(final int x, final int y) {
        final World world = world();
        final int size = world.getChunkSize();
        final int across = (int) Math.ceil((double) world.getWidthInTiles() / size);
        final int down = (int) Math.ceil((double) world.getHeightInTiles() / size);
        if (x < 0 || y < 0 || x >= across || y >= down) {
            return null;
        }
        final long id = (long) x << 32 | y;
        byte[] png = chunks.get(id);
        if (png == null) {
            png = AdventureRuntime.onUi(() -> drawChunk(world, x, y));
            chunks.put(id, png);
        }
        return png;
    }

    // Ported from WorldBackground.buildChunkTexture
    private static byte[] drawChunk(final World world, final int x, final int y) {
        final int tile = world.getTileSize();
        final int size = world.getChunkSize();
        final int px = size * tile;
        if (scratch == null || scratch.getWidth() != px) {
            if (scratch != null) {
                scratch.dispose();
            }
            scratch = new Pixmap(px, px, Pixmap.Format.RGBA8888);
            scratch.setBlending(Pixmap.Blending.None);
        }
        for (int cx = 0; cx < size; cx++) {
            for (int cy = 0; cy < size; cy++) {
                scratch.drawPixmap(world.getBiomeSprite(cx + size * x, cy + size * y), cx * tile, px - (cy + 1) * tile);
            }
        }
        return png(scratch);
    }

    /** One pixel per tile, top row first. Red is the tile's highest biome, green is 255 where the tile blocks walking. */
    public static synchronized byte[] grid() {
        final World world = world();
        final int width = world.getWidthInTiles();
        final int height = world.getHeightInTiles();
        final Pixmap grid = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    final int biome = World.highestBiome(world.getBiome(x, y)) & 0xff;
                    final int blocked = world.isColliding(x, y) ? 0xff : 0;
                    grid.drawPixel(x, height - 1 - y, biome << 24 | blocked << 16 | 0xff);
                }
            }
            return png(grid);
        } finally {
            grid.dispose();
        }
    }

    public static synchronized byte[] minimap() {
        return png(world().getBiomeImage());
    }

    /**
     * An atlas as JSON: image is the path of its picture, which {@link #res} serves, and frames lists each region with its place in the picture.
     * Null when the path leaves the adventure folders or names no atlas.
     */
    public static byte[] atlas(final String path) {
        final FileHandle file = file(path);
        if (file == null || !path.endsWith(".atlas")) {
            return null;
        }
        final TextureAtlasData data = new TextureAtlasData(file, file.parent(), false);
        if (data.getPages().isEmpty()) {
            return null;
        }
        final String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        final JsonObject json = new JsonObject();
        json.addProperty("image", dir + data.getPages().first().textureFile.name());
        final JsonArray frames = new JsonArray();
        for (final TextureAtlasData.Region region : data.getRegions()) {
            final JsonObject frame = new JsonObject();
            frame.addProperty("name", region.name);
            frame.addProperty("index", region.index);
            frame.addProperty("x", region.left);
            frame.addProperty("y", region.top);
            frame.addProperty("w", region.width);
            frame.addProperty("h", region.height);
            frames.add(frame);
        }
        json.add("frames", frames);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A PNG from the plane's or the common adventure folder, or null. */
    public static byte[] res(final String path) {
        final FileHandle file = file(path);
        return file == null || !path.endsWith(".png") ? null : file.readBytes();
    }

    /** The plane's file, else the common one, as the mobile client finds it. Null for a path that could climb out of those folders. */
    private static FileHandle file(final String path) {
        if (path == null || path.isEmpty() || path.contains("..") || path.contains("\\") || path.contains(":") || path.startsWith("/")) {
            return null;
        }
        final FileHandle file = Config.instance().getFile(path);
        return file != null && file.exists() ? file : null;
    }

    private static byte[] png(final Pixmap pixmap) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PixmapIO.PNG encoder = new PixmapIO.PNG();
        try {
            encoder.setFlipY(false);
            encoder.write(out, pixmap);
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            encoder.dispose();
        }
    }
}
