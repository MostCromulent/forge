package forge.web;

import forge.localinstance.properties.ForgeConstants;
import forge.web.ToBrowser.Playmat;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The table the board is played on: the match and texture images every Forge skin ships. */
final class Playmats {
    private static final String[] FILES = {"bg_match.jpg", "bg_texture.jpg"};

    private Playmats() {
    }

    /** Every playmat this installation has, as {@code {id, label}} pairs the browser shows as thumbnails. */
    static List<Playmat> list() {
        final List<Playmat> out = new ArrayList<>();
        for (final File skin : skins()) {
            for (final String file : FILES) {
                if (new File(skin, file).isFile()) {
                    out.add(new Playmat(skin.getName() + "/" + file, label(skin.getName(), file)));
                }
            }
        }
        return out;
    }

    /** The image bytes for an id from {@link #list()}, or null for anything else. */
    static byte[] image(final String id) {
        final int slash = id == null ? -1 : id.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        final String skin = id.substring(0, slash);
        final String file = id.substring(slash + 1);
        if (!List.of(FILES).contains(file)) {
            return null;
        }
        for (final File dir : skins()) {
            if (dir.getName().equals(skin) && new File(dir, file).isFile()) {
                try {
                    return Files.readAllBytes(new File(dir, file).toPath());
                } catch (final IOException e) {
                    Logger.warn("Could not read playmat {}: {}", id, e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private static List<File> skins() {
        final List<File> out = new ArrayList<>();
        out.add(new File(ForgeConstants.DEFAULT_SKINS_DIR));
        final File[] installed = new File(ForgeConstants.CACHE_SKINS_DIR).listFiles(File::isDirectory);
        if (installed != null) {
            for (final File dir : installed) {
                out.add(dir);
            }
        }
        return out;
    }

    private static String label(final String skin, final String file) {
        final String kind = "bg_match.jpg".equals(file) ? "table" : "felt";
        final String name = skin.substring(0, 1).toUpperCase() + skin.substring(1).replace('_', ' ');
        return name + " " + kind;
    }
}
