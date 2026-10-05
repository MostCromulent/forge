package forge.web;

import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The cell scan follows the desktop FSkin exactly, so an index in the shared preferences picks the same picture in both clients. */
final class SkinSprites {
    private static List<BufferedImage> avatars;
    private static BufferedImage manaIcons;
    private static BufferedImage abilityIcons;
    private static List<BufferedImage> sleeves;
    /** The sheets campaign icons are cut from, by file name. */
    private static final Map<String, BufferedImage> iconSheets = new HashMap<>();
    private static final Set<FSkinProp> CAMPAIGN_ICONS = Set.of(FSkinProp.IMG_AETHER_SHARD, FSkinProp.IMG_PW_BADGE_COMMON,
            FSkinProp.IMG_SPELLBOOK, FSkinProp.IMG_MULTIVERSE, FSkinProp.ICO_QUEST_COINSTACK, FSkinProp.ICO_QUEST_LIFE,
            // The icons Quest's reward messages name
            FSkinProp.ICO_QUEST_GOLD, FSkinProp.ICO_QUEST_COIN, FSkinProp.ICO_QUEST_HEART, FSkinProp.ICO_QUEST_NOTES, FSkinProp.ICO_QUEST_BOX);
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
        return encoded.computeIfAbsent((avatar ? "a" : "s") + index, k -> encode(cells.get(index), k));
    }

    /** PNG bytes of one mana or tap symbol, cut from the skin's icon sheet, or null for a symbol it has no image for. */
    static byte[] manaPng(final String symbol) {
        final FSkinProp prop = FSkinProp.MANA_IMG.get(symbol);
        if (prop == null) {
            return null;
        }
        return encoded.computeIfAbsent("m" + symbol, k -> cell(manaSheet(), prop, k));
    }

    /** PNG bytes of one keyword's icon, named by its FSkinProp, or null for a keyword the skin has no picture for. */
    static byte[] abilityPng(final String name) {
        final FSkinProp prop = prop(name);
        if (prop == null || !name.startsWith("IMG_ABILITY_")) {
            return null;
        }
        return encoded.computeIfAbsent("k" + name, k -> cell(sheetFor(prop), prop, k));
    }

    /** PNG bytes of one of the skin icons a campaign shows, named by its FSkinProp, or null for any other name. */
    static byte[] iconPng(final String name) {
        final FSkinProp prop = prop(name);
        if (prop == null || !CAMPAIGN_ICONS.contains(prop)) {
            return null;
        }
        return encoded.computeIfAbsent("c" + name, k -> cell(iconSheet(prop.getType().getFilename()), prop, k));
    }

    private static FSkinProp prop(final String name) {
        try {
            return FSkinProp.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static synchronized BufferedImage iconSheet(final String file) {
        return iconSheets.computeIfAbsent(file, f -> read(skinFile(f)));
    }

    private static synchronized BufferedImage manaSheet() {
        if (manaIcons == null) {
            manaIcons = read(skinFile(ForgeConstants.SPRITE_MANAICONS_FILE));
        }
        return manaIcons;
    }

    /** The ring-bearer's badge is on the mana sheet; every other ability icon is on its own. */
    private static synchronized BufferedImage sheetFor(final FSkinProp prop) {
        if (prop == FSkinProp.IMG_ABILITY_RINGBEARER) {
            return manaSheet();
        }
        if (abilityIcons == null) {
            abilityIcons = read(skinFile(ForgeConstants.SPRITE_ABILITY_FILE));
        }
        return abilityIcons;
    }

    private static byte[] cell(final BufferedImage sheet, final FSkinProp prop, final String name) {
        final int[] at = prop.getCoords();
        if (sheet == null || at.length < 4 || at[0] + at[2] > sheet.getWidth() || at[1] + at[3] > sheet.getHeight()) {
            return null;
        }
        return encode(sheet.getSubimage(at[0], at[1], at[2], at[3]), name);
    }

    private static List<BufferedImage> avatars() {
        if (avatars == null) {
            final File sheet = skinFile(ForgeConstants.SPRITE_AVATARS_FILE);
            avatars = new ArrayList<>();
            // The top-left cell is not an avatar
            cut(sheet, 100, 100, true, avatars);
        }
        return avatars;
    }

    private static byte[] encode(final BufferedImage image, final String name) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (final IOException e) {
            Logger.warn("Could not encode sprite {}: {}", name, e.getMessage());
            return null;
        }
        return out.toByteArray();
    }

    /** The player's skin if it has the sheet, otherwise the default skin. */
    private static File skinFile(final String name) {
        final String skin = FModel.getPreferences().getPref(FPref.UI_SKIN).toLowerCase().replace(' ', '_');
        final File preferred = new File((skin.isEmpty() || skin.equals("default") ? ForgeConstants.DEFAULT_SKINS_DIR
                : ForgeConstants.CACHE_SKINS_DIR + skin + "/") + name);
        return preferred.exists() ? preferred : new File(ForgeConstants.DEFAULT_SKINS_DIR + name);
    }

    private static BufferedImage read(final File file) {
        try {
            return file.exists() ? ImageIO.read(file) : null;
        } catch (final IOException e) {
            Logger.warn("Could not read {}: {}", file, e.getMessage());
            return null;
        }
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
        final BufferedImage sheet = read(file);
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
