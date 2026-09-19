package forge.web;

import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avatar and sleeve images cut from the skin's sprite sheets. The cell scan follows the desktop FSkin exactly, so an
 * index stored in the shared preferences picks the same picture in both clients.
 */
final class SkinSprites {
    private static List<BufferedImage> avatars;
    private static List<BufferedImage> sleeves;
    private static final Map<String, byte[]> encoded = new ConcurrentHashMap<>();

    private SkinSprites() {
    }

    static synchronized int avatarCount() {
        return avatars().size();
    }

    static synchronized int sleeveCount() {
        return sleeves().size();
    }

    /** PNG bytes of one avatar or sleeve, or null for an index the sheets do not have. */
    static byte[] png(final boolean avatar, final int index) {
        final List<BufferedImage> cells;
        synchronized (SkinSprites.class) {
            cells = avatar ? avatars() : sleeves();
        }
        if (index < 0 || index >= cells.size()) {
            return null;
        }
        return encoded.computeIfAbsent((avatar ? "a" : "s") + index, k -> {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(cells.get(index), "png", out);
            } catch (final IOException e) {
                Logger.warn("Could not encode sprite {}: {}", k, e.getMessage());
                return null;
            }
            return out.toByteArray();
        });
    }

    private static List<BufferedImage> avatars() {
        if (avatars == null) {
            final String skin = FModel.getPreferences().getPref(FPref.UI_SKIN).toLowerCase().replace(' ', '_');
            final File preferred = new File((skin.isEmpty() || skin.equals("default") ? ForgeConstants.DEFAULT_SKINS_DIR
                    : ForgeConstants.CACHE_SKINS_DIR + skin + "/") + ForgeConstants.SPRITE_AVATARS_FILE);
            final File sheet = preferred.exists() ? preferred : new File(ForgeConstants.DEFAULT_SKINS_DIR + ForgeConstants.SPRITE_AVATARS_FILE);
            avatars = new ArrayList<>();
            // The top-left cell is not an avatar
            cut(sheet, 100, 100, true, avatars);
        }
        return avatars;
    }

    private static List<BufferedImage> sleeves() {
        if (sleeves == null) {
            sleeves = new ArrayList<>();
            cut(new File(ForgeConstants.DEFAULT_SKINS_DIR + ForgeConstants.SPRITE_SLEEVES_FILE), 360, 500, false, sleeves);
            cut(new File(ForgeConstants.DEFAULT_SKINS_DIR + ForgeConstants.SPRITE_SLEEVES2_FILE), 360, 500, false, sleeves);
        }
        return sleeves;
    }

    // A cell whose centre pixel is transparent is empty and takes no index
    private static void cut(final File file, final int w, final int h, final boolean skipFirst, final List<BufferedImage> out) {
        final BufferedImage sheet;
        try {
            sheet = file.exists() ? ImageIO.read(file) : null;
        } catch (final IOException e) {
            Logger.warn("Could not read {}: {}", file, e.getMessage());
            return;
        }
        if (sheet == null) {
            return;
        }
        for (int y = 0; y + h <= sheet.getHeight(); y += h) {
            for (int x = 0; x + w <= sheet.getWidth(); x += w) {
                if (skipFirst && x == 0 && y == 0) {
                    continue;
                }
                if ((sheet.getRGB(x + w / 2, y + h / 2) >>> 24) == 0) {
                    continue;
                }
                out.add(sheet.getSubimage(x, y, w, h));
            }
        }
    }
}
