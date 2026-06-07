package forge.screens.home.sanctioned;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;

import com.google.common.collect.ImmutableList;

import forge.gui.GuiBase;
import forge.gui.UiCommand;
import forge.gui.WrapLayout;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.toolbox.FTextField;
import forge.util.CustomSleeves;
import forge.util.Localizer;
import forge.view.FDialog;

import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SleeveSelector extends FDialog {
    public static final String CUSTOM_LABEL_NAME = "SleeveLabelCustom";

    private final List<FLabel> selectables = new ArrayList<>();
    private final Map<Integer, FSkin.SkinImage> sleeveMap = FSkin.getSleeves();

    public SleeveSelector(final String playerName, final int currentIndex, final String parkedUrl, final boolean customSelected, final Collection<Integer> usedIndices) {
        this.setTitle(Localizer.getInstance().getMessage("lblSelectSleeveForPlayer", playerName));

        final JPanel pnlSleevePics = new JPanel(new WrapLayout());
        pnlSleevePics.setOpaque(false);

        final FLabel customCell = makeCustomLabel(parkedUrl, customSelected);
        pnlSleevePics.add(customCell);

        final int highlight = customSelected ? -1 : currentIndex;
        final FLabel initialSelection = makeSleeveLabel(sleeveMap.get(currentIndex), currentIndex, highlight);
        pnlSleevePics.add(initialSelection);
        for (final Integer i : sleeveMap.keySet()) {
            if (currentIndex != i) {
                pnlSleevePics.add(makeSleeveLabel(sleeveMap.get(i), i, highlight));
            }
        }

        final int width = this.getOwner().getWidth() * 3 / 4;
        final int height = this.getOwner().getHeight() * 3 / 4;
        this.setPreferredSize(new Dimension(width, height));
        this.setSize(width, height);

        final FScrollPane scroller = new FScrollPane(pnlSleevePics, false);
        scroller.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.add(scroller, "w 100%-24px!, pushy, growy, gap 12px 0 0 0");
        this.setDefaultFocus(customSelected ? customCell : initialSelection);
    }

    private FLabel makeSleeveLabel(final FSkin.SkinImage img0, final int index0, final int oldIndex) {
        final FLabel lbl = new FLabel.Builder().icon(img0).iconScaleFactor(0.95).iconAlignX(SwingConstants.CENTER)
                .iconInBackground(true).hoverable(true).selectable(true).selected(oldIndex == index0)
                .unhoveredAlpha(oldIndex == index0 ? 0.9f : 0.7f).build();

        sizeLabel(lbl);
        lbl.setName("SleeveLabel" + index0);

        if (oldIndex == index0) {
            lbl.setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_BORDERS).alphaColor(255), 3));
        }

        selectables.add(lbl);
        return lbl;
    }

    private FLabel makeCustomLabel(final String parkedUrl, final boolean selected) {
        final BufferedImage composite = customCellImage(parkedUrl);
        final FLabel.Builder builder = new FLabel.Builder().iconAlignX(SwingConstants.CENTER).iconInBackground(true)
                .hoverable(true).selectable(true).selected(selected).unhoveredAlpha(selected ? 0.9f : 0.7f);
        final FLabel lbl;
        if (composite != null) {
            lbl = builder.iconScaleFactor(0.95).build();
            lbl.setIcon(new ImageIcon(composite));
        } else {
            lbl = builder.icon(FSkin.getImage(FSkinProp.ICO_EDIT)).iconScaleFactor(0.5).build();
        }

        sizeLabel(lbl);
        lbl.setName(CUSTOM_LABEL_NAME);
        lbl.setToolTipText(Localizer.getInstance().getMessage("lblCustomSleeveUrl"));

        if (selected) {
            lbl.setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_BORDERS).alphaColor(255), 3));
        }

        selectables.add(lbl);
        return lbl;
    }

    private static BufferedImage editGlyph;
    private static boolean editGlyphLoaded;

    /** The custom sleeve's fetched image with the edit glyph composited at half opacity on top, or null if not cached. */
    private static BufferedImage customCellImage(final String parkedUrl) {
        if (parkedUrl == null || parkedUrl.isEmpty()) {
            return null;
        }
        final File f = new File(ForgeConstants.CACHE_SLEEVE_PICS_DIR, CustomSleeves.cacheFileName(parkedUrl));
        if (!f.exists()) {
            return null;
        }
        try {
            final BufferedImage sleeve = ImageIO.read(f);
            if (sleeve == null) {
                return null;
            }
            final BufferedImage out = new BufferedImage(sleeve.getWidth(), sleeve.getHeight(), BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(sleeve, 0, 0, null);
            final BufferedImage glyph = loadEditGlyph();
            if (glyph != null) {
                final int size = Math.min(sleeve.getWidth(), sleeve.getHeight()) / 2;
                final int x = (sleeve.getWidth() - size) / 2;
                final int y = (sleeve.getHeight() - size) / 2;
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g.drawImage(glyph, x, y, size, size, null);
            }
            g.dispose();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage loadEditGlyph() {
        if (!editGlyphLoaded) {
            editGlyphLoaded = true;
            try {
                final BufferedImage sprite = ImageIO.read(new File(ForgeConstants.DEFAULT_SKINS_DIR + ForgeConstants.SPRITE_ICONS_FILE));
                final int[] c = FSkinProp.ICO_EDIT.getCoords();
                editGlyph = sprite.getSubimage(c[0], c[1], c[2], c[3]);
            } catch (Exception e) {
                editGlyph = null;
            }
        }
        return editGlyph;
    }

    private static void sizeLabel(final FLabel lbl) {
        final Dimension size = new Dimension(100, 140);
        lbl.setPreferredSize(size);
        lbl.setMaximumSize(size);
        lbl.setMinimumSize(size);
    }

    public List<FLabel> getSelectables() {
        return this.selectables;
    }

    /** Modal prompt to enter and preview a custom sleeve image URL. Returns the entered URL, "" to clear, or null if cancelled. */
    public static String promptForUrl(final String currentUrl) {
        final Localizer localizer = Localizer.getInstance();
        final FTextField field = new FTextField.Builder().text(currentUrl == null ? "" : currentUrl)
                .ghostText(localizer.getMessage("lblCustomSleeveHttpsOnly")).build();
        field.setCaretPosition(0);
        final JLabel preview = new JLabel("", SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(200, 280));
        preview.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 90)));

        final Runnable doPreview = () -> {
            final String url = field.getText().trim();
            preview.setIcon(null);
            if (!CustomSleeves.isHttps(url)) {
                return;
            }
            GuiBase.getInterface().getImageFetcher().fetchSleeveImage(url, () -> {
                final File f = new File(ForgeConstants.CACHE_SLEEVE_PICS_DIR, CustomSleeves.cacheFileName(url));
                try {
                    final BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        preview.setIcon(fitIcon(img, 196, 276));
                    }
                } catch (Exception ignored) {
                }
            });
        };
        field.addActionListener(e -> doPreview.run());

        final FButton previewBtn = new FButton(localizer.getMessage("lblPreview"));
        previewBtn.setCommand((UiCommand) () -> doPreview.run());

        final JPanel panel = new JPanel(new MigLayout("insets 0, gap 6 6", "[grow][]", "[][][]"));
        panel.setOpaque(false);
        panel.add(new FLabel.Builder().text(localizer.getMessage("lblImageUrl")).build(), "span 2, gapbottom 2, wrap");
        panel.add(field, "growx, h 25!");
        panel.add(previewBtn, "h 25!, wrap");
        panel.add(preview, "span 2, align center, gaptop 6");

        if (CustomSleeves.isHttps(currentUrl)) {
            doPreview.run();
        }

        final int result = FOptionPane.showOptionDialog("", localizer.getMessage("lblCustomSleeveUrl"), null, panel,
                ImmutableList.of(localizer.getMessage("lblOK"), localizer.getMessage("lblCancel")), 0);
        return result == 0 ? field.getText().trim() : null;
    }

    private static ImageIcon fitIcon(final BufferedImage img, final int boxW, final int boxH) {
        final double scale = Math.min((double) boxW / img.getWidth(), (double) boxH / img.getHeight());
        final int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        final int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        return new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH));
    }
}
