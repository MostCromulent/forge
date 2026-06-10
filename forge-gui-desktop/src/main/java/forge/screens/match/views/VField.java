/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.screens.match.views;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

import javax.swing.JPanel;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;

import forge.deck.CommanderBracketCalculator;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.card.CounterEnumType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.controllers.CField;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinImage;
import forge.toolbox.FSkin.SkinnedPanel;
import forge.toolbox.special.ManaPoolPanel;
import forge.toolbox.special.PhaseIndicator;
import forge.toolbox.special.PlayerDetailsPanel;
import forge.util.Localizer;
import forge.view.arcane.PlayArea;
import net.miginfocom.swing.MigLayout;

/** 
 * Assembles Swing components of a player field instance.
 * 
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public class VField implements IVDoc<CField> {
    private final static int LIFE_CRITICAL = 5;
    private final static int POISON_CRITICAL = 8;

    // Fields used with interface IVDoc
    private final CField control;
    private DragCell parentCell;
    private final EDocID docID;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblField"));

    // Other fields
    private final CMatchUI matchUI;
    private final PlayerView player;
    private boolean commanderBracketTooltipCalculated = false;
    private String commanderBracketTooltipLine;

    // Top-level containers
    private final FScrollPane scroller = new FScrollPane(false);
    private final PlayArea tabletop;
    private final SkinnedPanel avatarArea = new SkinnedPanel();

    private final PlayerDetailsPanel detailsPanel;
    private final ManaPoolPanel manaPool;

    // Avatar area
    private final AvatarLabel avatarLabel = new AvatarLabel();

    private final PhaseIndicator phaseIndicator = new PhaseIndicator();

    private final Border borderAvatarSimple = new LineBorder(new Color(0, 0, 0, 0), 1);
    private final Border borderAvatarHighlighted = new LineBorder(Color.red, 2);

    private final TabDiffTracker tabDiff = new TabDiffTracker();


    //========= Constructor
    /**
     * Assembles Swing components of a player field instance.
     * 
     * @param p &emsp; {@link forge.game.player.Player}
     * @param id0 &emsp; {@link forge.gui.framework.EDocID}
     */
    public VField(final CMatchUI matchUI, final EDocID id0, final PlayerView p, final boolean mirror) {
        this.docID = id0;

        this.matchUI = matchUI;
        this.player = p;
        if (p != null) { tab.setText(Localizer.getInstance().getMessage("lblPlayField", p.getName())); }
        else { tab.setText(Localizer.getInstance().getMessage("lblNoPlayerForEDocID", docID.toString())); }

        detailsPanel = new PlayerDetailsPanel(player, CMatchUI.FLOATING_ZONE_TYPES);
        manaPool = new ManaPoolPanel(player);
        avatarLabel.setLibraryComponent(detailsPanel.getLblLibrary());
        detailsPanel.setAvatarComponent(avatarLabel);

        // TODO player is hard-coded into tabletop...should be dynamic
        // (haven't looked into it too deeply). Doublestrike 12-04-12
        tabletop = new PlayArea(matchUI, scroller, mirror, player, ZoneType.Battlefield);

        control = new CField(matchUI, player, this);

        avatarLabel.setFocusable(false);

        avatarArea.setOpaque(false);
        avatarArea.setBackground(FSkin.getColor(FSkin.Colors.CLR_HOVER));
        avatarArea.setLayout(new MigLayout("insets 0, gap 0"));
        avatarArea.add(avatarLabel, "w 100%!, h 100%!");
        avatarArea.add(manaPool, "pos 2% 3 98% 44");
        avatarArea.setComponentZOrder(manaPool, 0);

        // Player area hover effect
        avatarArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(final MouseEvent e) {
                avatarArea.setOpaque(true);
                if (!isHighlighted()) {
                    avatarArea.setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_BORDERS)));
                }
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                avatarArea.setOpaque(false);
                if (!isHighlighted()) {
                    avatarArea.setBorder(borderAvatarSimple);
                }
            }
        });

        tabletop.setBorder(new FSkin.MatteSkinBorder(0, 1, 0, 0, FSkin.getColor(FSkin.Colors.CLR_BORDERS)));
        tabletop.setOpaque(false);

        scroller.setViewportView(this.tabletop);

        updateDetails();
    }

    @Override
    public void populate() {
        final JPanel pnl = parentCell.getBody();
        pnl.setLayout(new MigLayout("insets 0, gap 0"));

        pnl.add(avatarArea, "w 10%!, h 33%!");
        pnl.add(phaseIndicator, "w 5%!, h 100%!, span 1 2");
        pnl.add(scroller, "w 85%!, h 100%!, span 1 2, wrap");
        pnl.add(detailsPanel, "w 10%!, h 66%!, gapleft 1px");
    }

    @Override
    public EDocID getDocumentID() {
        return docID;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CField getLayoutControl() {
        return control;
    }

    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return this.parentCell;
    }

    public PlayArea getTabletop() {
        return this.tabletop;
    }

    public JPanel getAvatarArea() {
        return this.avatarArea;
    }

    public PhaseIndicator getPhaseIndicator() {
        return phaseIndicator;
    }

    public PlayerDetailsPanel getDetailsPanel() {
        return detailsPanel;
    }

    private boolean isHighlighted() {
        return control.getMatchUI().isHighlighted(player);
    }

    public void setAvatar(final SkinImage avatar) {
        avatarLabel.setAvatarImage(avatar);
    }

    public void updateManaPool() {
        manaPool.update();
    }
    public void setupManaActions(final Function<Byte, Boolean> manaAction) {
        manaPool.setupMouseActions(manaAction);
    }
    public void updateZones() {
        detailsPanel.updateZones();
    }

    public void updateTabLabel() {
        if (player == null) {
            return;
        }
        final int delta = tabDiff.update(tabletop.getCardPanels().size(), tab);
        String label = Localizer.getInstance().getMessage("lblPlayField", player.getName());
        if (delta != 0) {
            label += " (" + delta + " new)";
        }
        tab.setText(label);
        tab.setToolTipText(label);
    }

    public void updateDetails() {
        // The highest-precedence active counter (poison > energy > experience > rad > ticket) shows on the avatar.
        final int poison = player.getCounters(CounterEnumType.POISON);
        final int energy = player.getCounters(CounterEnumType.ENERGY);
        final int experience = player.getCounters(CounterEnumType.EXPERIENCE);
        final int rad = player.getCounters(CounterEnumType.RAD);
        final int ticket = player.getCounters(CounterEnumType.TICKET);
        if (poison > 0) {
            avatarLabel.setCounter(FSkin.getImage(FSkinProp.IMG_POISON), poison, poison >= POISON_CRITICAL);
        } else if (energy > 0) {
            avatarLabel.setCounter(FSkin.getImage(FSkinProp.IMG_ENERGY), energy, false);
        } else if (experience > 0) {
            avatarLabel.setCounter(FSkin.getImage(FSkinProp.IMG_EXPERIENCE), experience, false);
        } else if (rad > 0) {
            avatarLabel.setCounter(FSkin.getImage(FSkinProp.IMG_RAD), rad, false);
        } else if (ticket > 0) {
            avatarLabel.setCounter(FSkin.getImage(FSkinProp.IMG_TICKET), ticket, false);
        } else {
            avatarLabel.setCounter(null, 0, false);
        }
        avatarLabel.repaint();

        final boolean highlighted = isHighlighted();
        this.avatarArea.setBorder(highlighted ? borderAvatarHighlighted : borderAvatarSimple);
        this.avatarArea.setOpaque(highlighted);
        this.avatarArea.setToolTipText(getPlayerDetailsHtml());
    }

    /** Paints the player's avatar contained and centred (matching the deck sleeve) with the life total as a corner badge. */
    private final class AvatarLabel extends JPanel {
        private SkinImage avatar;
        private Component library;
        private SkinImage counterIcon;
        private int counterCount;
        private boolean counterCritical;

        AvatarLabel() {
            setOpaque(false);
        }

        void setLibraryComponent(final Component c) {
            this.library = c;
        }

        void setCounter(final SkinImage icon, final int count, final boolean critical) {
            this.counterIcon = icon;
            this.counterCount = count;
            this.counterCritical = critical;
            repaint();
        }

        void setAvatarImage(final SkinImage image) {
            this.avatar = image;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Avatar (square) scaled to the shared width, shrunk to fit the available height (its own and the library's) so it never crops.
            int side = Math.min(Math.round(w * PlayerDetailsPanel.AVATAR_WIDTH_PCT), h - 2);
            if (library != null && library.getHeight() > 0) {
                side = Math.min(side, (int) ((library.getHeight() - 2) / PlayerDetailsPanel.LIBRARY_ASPECT));
            }
            side = Math.max(8, side);
            final int ax = (w - side) / 2;
            final int ay = (h - side) / 2;
            if (avatar != null) {
                FSkin.drawImage(g2, avatar, ax, ay, side, side);
            }

            if (player != null) {
                final int life = player.getLife();
                final String text = String.valueOf(life);
                FSkin.setGraphicsFont(g2, FSkin.getBoldFont(PlayerDetailsPanel.badgeFontSize(w)));
                final java.awt.FontMetrics fm = g2.getFontMetrics();
                final int badgeW = fm.stringWidth(text) + 10;
                final int badgeH = fm.getAscent() + 6;
                final int bx = ax + side - badgeW;
                final int by = Math.min(ay + side, h) - badgeH + 2;
                g2.setColor(new Color(0, 0, 0, 185));
                g2.fillRoundRect(bx, by, badgeW, badgeH, 8, 8);
                g2.setColor(life > LIFE_CRITICAL ? Color.WHITE : Color.RED);
                g2.drawString(text, bx + 5, by + 3 + fm.getAscent());

                // Active counter (poison/energy/...) as a matching badge on the avatar's bottom-left.
                if (counterIcon != null && counterCount > 0) {
                    final String ctext = String.valueOf(counterCount);
                    final int iconSz = fm.getAscent();
                    final int cw = 4 + iconSz + 2 + fm.stringWidth(ctext) + 5;
                    final int cx = ax;
                    g2.setColor(new Color(0, 0, 0, 185));
                    g2.fillRoundRect(cx, by, cw, badgeH, 8, 8);
                    FSkin.drawImage(g2, counterIcon, cx + 4, by + (badgeH - iconSz) / 2, iconSz, iconSz);
                    g2.setColor(counterCritical ? Color.RED : Color.WHITE);
                    g2.drawString(ctext, cx + 4 + iconSz + 2, by + 3 + fm.getAscent());
                }
            }
            g2.dispose();
        }
    }

    private String getPlayerDetailsHtml() {
        final String detailsHtml = player.getDetailsHtml();
        final String commanderBracketLine = getCommanderBracketTooltipLine();
        if (commanderBracketLine == null) {
            return detailsHtml;
        }

        final String nameSeparator = "<hr/>";
        final int insertIndex = detailsHtml.indexOf(nameSeparator);
        if (insertIndex < 0) {
            return detailsHtml;
        }

        final int lineIndex = insertIndex + nameSeparator.length();
        return detailsHtml.substring(0, lineIndex)
                + commanderBracketLine + "<br/>"
                + detailsHtml.substring(lineIndex);
    }

    private String getCommanderBracketTooltipLine() {
        if (commanderBracketTooltipCalculated) {
            return commanderBracketTooltipLine;
        }

        commanderBracketTooltipCalculated = true;
        if (matchUI == null || matchUI.getGameView() == null || !matchUI.getGameView().isCommander()) {
            return null;
        }
        final GameType gameType = matchUI.getGameView().getGameType();
        if (gameType == GameType.Adventure || gameType == GameType.AdventureEvent) {
            return null;
        }
        final int maximumBracket = FModel.getPreferences().getPrefInt(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET);
        if (maximumBracket < 1 || maximumBracket > 4) {
            return null;
        }
        final Deck deck = matchUI.getGameView().getDeck(player);
        if (deck == null) {
            return null;
        }

        commanderBracketTooltipLine = Localizer.getInstance().getMessage("lblBracket")
                + ": " + CommanderBracketCalculator.getBracket(deck);
        return commanderBracketTooltipLine;
    }

}
