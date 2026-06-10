package forge.toolbox.special;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.apache.commons.text.WordUtils;

import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gui.MouseUtil;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FLabel;
import forge.toolbox.FMouseAdapter;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinImage;
import forge.toolbox.FSkin.SkinnedPanel;
import forge.trackable.TrackableProperty;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

public class PlayerDetailsPanel extends JPanel {
    private static final long serialVersionUID = -6531759554646891983L;

    private static final List<ZoneType> CORE_ZONES = List.of(ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Flashback);
    private static final String ZONE_ROW = "w 100%!, h 19px!";

    /** Fraction of the panel width occupied by the avatar and the deck sleeve, so the two images line up. */
    public static final float AVATAR_WIDTH_PCT = 0.96f;

    /** Library card height / width, used to scale the avatar and card together to fit the available height. */
    public static final float LIBRARY_ASPECT = 500f / 360f;

    /** Font size for the life/library corner badges, shared so both read identically. */
    public static int badgeFontSize(final int width) {
        return Math.max(11, Math.min(15, width / 6));
    }

    /**
     * Area-averaged (cached) copy of {@code img} at the device resolution for a {@code w}x{@code h} logical draw, so a large
     * downscale stays smooth instead of the single-pass scale {@link FSkin#drawImage} redoes every paint. The target is
     * quantised to bound the cache during resizes and capped at the source size so it never upscales.
     */
    public static SkinImage scaledForDraw(final SkinImage img, final int w, final int h,
                                          final double deviceScale, final int srcW, final int srcH) {
        int tw = ((int) Math.round(w * deviceScale) + 7) / 8 * 8;
        tw = Math.max(8, Math.min(srcW, tw));
        final int th = Math.max(8, Math.round(tw * (float) srcH / srcW));
        return img.resize(tw, th);
    }

    private final PlayerView player;

    private final DeckSleeveLabel libraryLabel;
    private final PortraitStack portrait;
    private final SkinnedPanel zoneList;
    private final Map<ZoneType, ZoneRow> coreLabels = new EnumMap<>(ZoneType.class);
    private final Map<ZoneType, ZoneRow> extraLabels = new EnumMap<>(ZoneType.class);
    private final List<ZoneType> shownExtras = new ArrayList<>();

    public PlayerDetailsPanel(final PlayerView player, final EnumSet<ZoneType> supportedZones) {
        this.player = player;

        libraryLabel = new DeckSleeveLabel();

        coreLabels.put(ZoneType.Hand, new ZoneRow(ZoneType.Hand, "lblHandNOfMax", PlayerView::getMaxHandString));
        coreLabels.put(ZoneType.Graveyard, new ZoneRow(ZoneType.Graveyard, "lblGraveyardNCardsNTypes", p -> p.getZoneTypes(TrackableProperty.Graveyard)));
        coreLabels.put(ZoneType.Exile, new ZoneRow(ZoneType.Exile, "lblExileNCards", null));
        coreLabels.put(ZoneType.Flashback, new ZoneRow(ZoneType.Flashback, "lblFlashbackNCards", null));

        for (final ZoneType zone : supportedZones) {
            if (zone == ZoneType.Library || coreLabels.containsKey(zone)) {
                continue;
            }
            extraLabels.put(zone, new ZoneRow(zone, extraTooltipKey(zone), null));
        }

        setOpaque(false);
        setLayout(new MigLayout("insets 0, gap 0, wrap, hidemode 3"));

        zoneList = new SkinnedPanel(new MigLayout("insets 1, gap 0, wrap, hidemode 3"));
        zoneList.setOpaque(false);
        for (final ZoneType zone : CORE_ZONES) {
            zoneList.add(coreLabels.get(zone), ZONE_ROW);
        }

        portrait = new PortraitStack();
        add(portrait, "w 100%!, growy, pushy, gap 0");
        add(zoneList, "w 100%!, gaptop 2px");

        updateZones();
    }

    /** Hosts the avatar tile above the library sleeve so the two scale together as one centred unit. */
    public void addAvatarArea(final Component avatarArea) {
        portrait.setAvatar(avatarArea);
    }

    /** Floats the mana strip over the top of the avatar, spanning the full column width rather than the centred avatar. */
    public void setManaOverlay(final Component mana) {
        portrait.setMana(mana);
    }

    private static String extraTooltipKey(final ZoneType zone) {
        switch (zone) {
            case Flashback: return "lblFlashbackNCards";
            case Command: return "lblCommandZoneNCards";
            case Sideboard: return "lblSideboardNCards";
            default: return null;
        }
    }

    public static FSkinProp iconFromZone(ZoneType zoneType) {
        return FSkinProp.iconFromZone(zoneType, false);
    }

    public Component getLblLibrary() {
        return libraryLabel;
    }

    /** Core zones always show; extra zones (command, sideboard, etc.) appear only while they hold cards. */
    public void updateZones() {
        libraryLabel.onContentUpdate();
        for (final ZoneRow row : coreLabels.values()) {
            row.onContentUpdate();
        }

        final List<ZoneType> nowVisible = new ArrayList<>();
        for (final ZoneType zone : extraLabels.keySet()) {
            if (player.getZoneSize(zone) > 0) {
                nowVisible.add(zone);
            }
        }
        if (!nowVisible.equals(shownExtras)) {
            for (final ZoneType zone : shownExtras) {
                zoneList.remove(extraLabels.get(zone));
            }
            for (final ZoneType zone : nowVisible) {
                zoneList.add(extraLabels.get(zone), ZONE_ROW);
            }
            shownExtras.clear();
            shownExtras.addAll(nowVisible);
            zoneList.revalidate();
            revalidate();
            repaint();
        }
        for (final ZoneType zone : nowVisible) {
            extraLabels.get(zone).onContentUpdate();
        }
        restripe();
    }

    private void restripe() {
        int i = 0;
        for (final ZoneType zone : CORE_ZONES) {
            coreLabels.get(zone).setStripe(i++ % 2 == 0);
        }
        for (final ZoneType zone : shownExtras) {
            extraLabels.get(zone).setStripe(i++ % 2 == 0);
        }
    }

    public void setupMouseActions(Function<ZoneType, Runnable> zoneActionFactory,
                                  BiConsumer<ZoneType, MouseEvent> zoneRightClick) {
        wireZone(libraryLabel, ZoneType.Library, zoneActionFactory, zoneRightClick);
        for (final Map.Entry<ZoneType, ZoneRow> entry : coreLabels.entrySet()) {
            wireZone(entry.getValue(), entry.getKey(), zoneActionFactory, zoneRightClick);
        }
        for (final Map.Entry<ZoneType, ZoneRow> entry : extraLabels.entrySet()) {
            wireZone(entry.getValue(), entry.getKey(), zoneActionFactory, zoneRightClick);
        }
    }

    private void wireZone(final JComponent target, final ZoneType zone,
                          final Function<ZoneType, Runnable> zoneActionFactory,
                          final BiConsumer<ZoneType, MouseEvent> zoneRightClick) {
        final Runnable action = zoneActionFactory.apply(zone);
        final FMouseAdapter adapter = new FMouseAdapter() {
            @Override
            public void onLeftClick(MouseEvent e) {
                action.run();
            }
            @Override
            public void onRightClick(MouseEvent e) {
                if (zoneRightClick != null) {
                    zoneRightClick.accept(zone, e);
                }
            }
        };
        target.addMouseListener(adapter);
        for (final Component child : target.getComponents()) {
            child.addMouseListener(adapter);
        }
    }

    private static String zoneTooltip(final ZoneType zone, final int count, final String tooltipKey,
                                      final Function<PlayerView, Object> extraArg, final PlayerView player) {
        if (tooltipKey == null) {
            return String.format("%s (%d)", WordUtils.capitalize(zone.getTranslatedName()), count);
        }
        final Localizer localizer = Localizer.getInstance();
        if (extraArg == null) {
            return localizer.getMessage(tooltipKey, count);
        }
        return localizer.getMessage(tooltipKey, count, extraArg.apply(player));
    }

    /** Shrinks the label's font until the full text fits its current width, so long zone names never truncate. */
    private static void fitFont(final FLabel label, final String text) {
        final int avail = label.getWidth();
        if (avail <= 0) {
            return;
        }
        for (int size = 10; size >= 6; size--) {
            final Font font = FSkin.getFont(size).getBaseFont();
            if (label.getFontMetrics(font).stringWidth(text) <= avail) {
                label.setFont(font);
                return;
            }
        }
        label.setFont(FSkin.getFont(6).getBaseFont());
    }

    private static SkinImage sleeveImage(final PlayerView player) {
        final Map<Integer, SkinImage> sleeves = FSkin.getSleeves();
        final SkinImage image = sleeves.get(player.getSleeveIndex());
        return image != null ? image : sleeves.get(0);
    }

    /** A single zone row: leading zone icon, the zone's name, and its right-aligned card count. Highlights on hover. */
    private class ZoneRow extends SkinnedPanel {
        private final ZoneType zone;
        private final String tooltipKey;
        private final Function<PlayerView, Object> tooltipExtraArg;
        private final FLabel countLabel;
        private boolean shaded;
        private boolean hovered;

        ZoneRow(final ZoneType zone, final String tooltipKey, final Function<PlayerView, Object> tooltipExtraArg) {
            this.zone = zone;
            this.tooltipKey = tooltipKey;
            this.tooltipExtraArg = tooltipExtraArg;

            setOpaque(false);
            setLayout(new MigLayout("insets 0 4 0 6, gap 0, fillx, filly", "", "[center]"));

            final FLabel icon = new FLabel.Builder().icon(FSkin.getImage(iconFromZone(zone)).resize(14, 14))
                    .iconScaleAuto(false).build();
            final String displayName = WordUtils.capitalize(zone.getTranslatedName());
            final FLabel name = new FLabel.Builder().text(displayName).fontSize(10).fontAlign(SwingConstants.LEFT).build();
            name.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(final ComponentEvent e) {
                    fitFont(name, displayName);
                }
            });
            countLabel = new FLabel.Builder().fontSize(10).fontStyle(Font.BOLD).fontAlign(SwingConstants.RIGHT).build();

            add(icon, "w 14px!, h 14px!, gapright 5");
            add(name, "growx, pushx, wmin 0");
            add(countLabel, "gapleft 4, al right");

            final MouseAdapter hover = new MouseAdapter() {
                @Override
                public void mouseEntered(final MouseEvent e) {
                    setHovered(true);
                    MouseUtil.setCursor(Cursor.HAND_CURSOR);
                }
                @Override
                public void mouseExited(final MouseEvent e) {
                    if (getMousePosition() == null) {
                        setHovered(false);
                        MouseUtil.resetCursor();
                    }
                }
            };
            addMouseListener(hover);
            for (final Component child : getComponents()) {
                child.addMouseListener(hover);
            }
        }

        void setStripe(final boolean shaded) {
            if (this.shaded == shaded) {
                return;
            }
            this.shaded = shaded;
            refreshBackground();
        }

        private void setHovered(final boolean hovered) {
            if (this.hovered == hovered) {
                return;
            }
            this.hovered = hovered;
            refreshBackground();
        }

        private void refreshBackground() {
            if (hovered) {
                setOpaque(true);
                setBackground(FSkin.getColor(FSkin.Colors.CLR_HOVER));
            } else if (shaded) {
                setOpaque(true);
                setBackground(FSkin.getColor(FSkin.Colors.CLR_ZEBRA));
            } else {
                setOpaque(false);
            }
            repaint();
        }

        void onContentUpdate() {
            final int count = player.getZoneSize(zone);
            countLabel.setText(String.valueOf(count));
            if (count > 0) {
                countLabel.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
            } else {
                countLabel.setForeground(new Color(150, 156, 165));
            }
            setToolTipText(zoneTooltip(zone, count, tooltipKey, tooltipExtraArg, player));
        }
    }

    /** Avatar (square) above the library sleeve (card), both at one shared width, centred as a unit in the available height. */
    private final class PortraitStack extends JPanel {
        private Component avatar;
        private Component mana;

        PortraitStack() {
            setOpaque(false);
            setLayout(null);
            add(libraryLabel);
        }

        void setAvatar(final Component c) {
            avatar = c;
            add(c);
            revalidate();
        }

        void setMana(final Component c) {
            mana = c;
            add(c);
            setComponentZOrder(c, 0); // keep the floating mana above the avatar
            revalidate();
        }

        @Override
        public void doLayout() {
            final int w = getWidth();
            final int h = getHeight();
            final int gap = 2;
            // Largest width that lets the square avatar and the card library both fit the height; the column width otherwise caps it.
            int side = Math.min(w, (int) ((h - gap) / (1 + LIBRARY_ASPECT)));
            side = Math.max(8, side);
            final int libH = Math.round(side * LIBRARY_ASPECT);
            final int x = (w - side) / 2;
            final int y = Math.max(0, (h - side - gap - libH) / 2);
            if (avatar != null) {
                avatar.setBounds(x, y, side, side);
            }
            libraryLabel.setBounds(x, y + side + gap, side, libH);
            if (mana != null) {
                final int pad = 3;
                mana.setBounds(pad, 4, w - 2 * pad, Math.min(side, 52));
            }
        }
    }

    /** The Library, drawn as the player's deck sleeve in a recessed slot with the count in a corner badge. Highlights on hover. */
    private class DeckSleeveLabel extends JComponent {
        private static final int SRC_W = 360;
        private static final int SRC_H = 500;

        private final SkinImage sleeve;
        private int count;
        private boolean hovered;

        DeckSleeveLabel() {
            this.sleeve = sleeveImage(player);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(final MouseEvent e) {
                    hovered = true;
                    MouseUtil.setCursor(Cursor.HAND_CURSOR);
                    repaint();
                }
                @Override
                public void mouseExited(final MouseEvent e) {
                    hovered = false;
                    MouseUtil.resetCursor();
                    repaint();
                }
            });
        }

        void onContentUpdate() {
            count = player.getZoneSize(ZoneType.Library);
            setToolTipText(zoneTooltip(ZoneType.Library, count, "lblLibraryNCards", null, player));
            repaint();
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 44);
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }

            // Card scaled to AVATAR_WIDTH_PCT of width, capped to fit the available height so it never crops.
            int dw = Math.min(Math.round(w * AVATAR_WIDTH_PCT), (int) ((h - 2) / LIBRARY_ASPECT));
            dw = Math.max(8, dw);
            final int dh = Math.round(dw * LIBRARY_ASPECT);
            final int dx = (w - dw) / 2;
            final int dy = (h - dh) / 2;

            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(new Color(0, 0, 0, 55));
            g2.fillRect(0, 0, w, h);
            FSkin.drawImage(g2, scaledForDraw(sleeve, dw, dh, g2.getTransform().getScaleX(), SRC_W, SRC_H), dx, dy, dw, dh);

            final String cnt = String.valueOf(count);
            FSkin.setGraphicsFont(g2, FSkin.getBoldFont(badgeFontSize(w)));
            final FontMetrics cfm = g2.getFontMetrics();
            final int badgeW = cfm.stringWidth(cnt) + 10;
            final int badgeH = cfm.getAscent() + 6;
            final int badgeX = dx + dw - badgeW;
            final int badgeY = Math.min(dy + dh, h) - badgeH - 4;
            g2.setColor(new Color(0, 0, 0, 185));
            g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString(cnt, badgeX + 5, badgeY + 3 + cfm.getAscent());

            if (hovered) {
                g2.setColor(new Color(255, 255, 255, 28));
                g2.fillRect(dx, dy, dw, Math.min(dh, h - dy));
            }

            g2.dispose();
        }
    }
}
