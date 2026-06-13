package forge.toolbox.special;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.SwingConstants;

import forge.card.mana.ManaAtom;
import forge.game.player.PlayerView;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FLabel;
import forge.toolbox.FMouseAdapter;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedPanel;
import net.miginfocom.swing.MigLayout;

/**
 * Compact single-row floating-mana strip shown at the battlefield corner nearest a player's lands.
 * Renders a pip only for the colours currently in the pool, and hides itself entirely when the pool is empty.
 */
public class ManaPoolPanel extends SkinnedPanel {
    private static final String[] COLORS = {"W", "U", "B", "R", "G", "C"};
    private static final int PIP_H = 26;
    private static final int PIP_ICON_W = Math.round(PIP_H * 0.80f);
    private static final int PIP_FONT_PX = 16;

    private final PlayerView player;
    private final List<ManaPip> pips = new ArrayList<>();
    private final MigLayout layout = new MigLayout("insets 2, gap 2, alignx left, aligny center, hidemode 3");

    public ManaPoolPanel(final PlayerView player) {
        this.player = player;
        setOpaque(false);
        setLayout(layout);
        for (final String color : COLORS) {
            final ManaPip pip = new ManaPip(color);
            pips.add(pip);
            add(pip, "w " + PIP_ICON_W + "px!, h " + PIP_H + "px!");
        }
        setVisible(false);
    }

    @Override
    protected void paintComponent(final Graphics g) {
        // Draw the backing only around the visible pips, so it fits one or two rows.
        Rectangle box = null;
        for (final ManaPip pip : pips) {
            if (pip.isVisible()) {
                box = box == null ? pip.getBounds() : box.union(pip.getBounds());
            }
        }
        if (box != null) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 140));
            g2.fillRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 8, 8);
            g2.setColor(new Color(255, 255, 255, 60));
            g2.drawRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 8, 8);
            g2.dispose();
        }
        super.paintComponent(g);
    }

    public void update() {
        final List<ManaPip> visiblePips = new ArrayList<>();
        for (final ManaPip pip : pips) {
            final int count = player.getMana(ManaAtom.fromName(pip.color));
            pip.setVisible(count > 0);
            if (count > 0) {
                pip.setText(String.valueOf(count));
                visiblePips.add(pip);
            }
        }
        setVisible(!visiblePips.isEmpty());
        if (!visiblePips.isEmpty()) {
            int maxLen = 1;
            for (final ManaPip pip : visiblePips) {
                maxLen = Math.max(maxLen, pip.getText().length());
            }
            // Size each pip to its content: icon plus the widest count actually present.
            final Font numFont = FSkin.getFont(PIP_FONT_PX).getBaseFont().deriveFont(Font.BOLD);
            final int pipW = PIP_ICON_W + 5 + getFontMetrics(numFont).stringWidth("8".repeat(maxLen));
            for (final ManaPip pip : visiblePips) {
                pip.setFont(numFont);
                // Count sits just right of the icon (left inset) and a hair low so it optically centres on it.
                pip.setBorder(BorderFactory.createEmptyBorder(2, PIP_ICON_W + 3, 0, 0));
                layout.setComponentConstraints(pip, "w " + pipW + "px!, h " + PIP_H + "px!");
            }
        }
        revalidate();
        repaint();
    }

    public void setupMouseActions(final Function<Byte, Boolean> manaAction) {
        for (final ManaPip pip : pips) {
            pip.addMouseListener(new FMouseAdapter() {
                @Override
                public void onLeftClick(final MouseEvent e) {
                    //if shift key down, keep using mana until it runs out or no longer can be put towards the cost
                    final Byte mana = ManaAtom.fromName(pip.color);
                    do { manaAction.apply(mana); }
                    while (e.isShiftDown());
                }
            });
        }
    }

    private static class ManaPip extends FLabel {
        private final String color;

        ManaPip(final String color) {
            super(new FLabel.Builder().icon(FSkin.getImage(FSkinProp.MANA_IMG.get(color)))
                    .opaque(false).fontSize(11).hoverable().unhoveredAlpha(1.0f).fontStyle(Font.BOLD)
                    .iconScaleFactor(0.80).iconInBackground().iconAlignX(SwingConstants.LEFT).fontAlign(SwingConstants.LEFT));
            this.color = color;
            setFocusable(false);
            setForeground(Color.WHITE);
        }
    }
}
