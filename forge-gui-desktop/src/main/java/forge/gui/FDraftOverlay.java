package forge.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import forge.ImageCache;
import forge.ImageKeys;
import forge.Singletons;
import forge.gui.framework.SDisplayUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.home.online.OnlineMenu;
import forge.toolbox.FMouseAdapter;
import forge.toolbox.FSkin;
import forge.view.FDialog;
import forge.view.FFrame;
import net.miginfocom.swing.MigLayout;

/**
 * Floating overlay window displayed during network draft sessions.
 * Shows pack/round info, a countdown timer, and neighbor seats with queue depths.
 *
 * Follows the same FDialog pattern as {@link FNetOverlay}.
 */
public enum FDraftOverlay {
    SINGLETON_INSTANCE;

    private static final String COORD_DELIM = ",";

    // Default window dimensions
    private static final int DEFAULT_WIDTH  = 400;
    private static final int DEFAULT_HEIGHT = 70;

    private final ForgePreferences prefs = FModel.getPreferences();
    private boolean hasBeenShown, locLoaded;

    private final FSkin.SkinnedLabel lblPackInfo  = new FSkin.SkinnedLabel("");
    private final FSkin.SkinnedLabel lblTimer     = new FSkin.SkinnedLabel("");
    private final JPanel pnlNeighbors = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));

    private static ImageIcon cardBackIcon;

    private String   leftName, rightName;
    private boolean  leftAI,   rightAI;
    private int      mySeat;
    private int      currentPack, totalPacks;
    private int      currentPick, currentPackSize, initialPackSize;
    private boolean  passingRight;
    private int[]    queueDepths = new int[0];

    /** Countdown timer (client-side fire-and-forget). */
    private Timer countdownTimer;
    private int               secondsRemaining;
    private boolean           waitingForPack;
    private boolean           timerDisabled;

    @SuppressWarnings("serial")
    private final FDialog window = new FDialog(false, true, "4") {
        @Override
        public void setLocationRelativeTo(Component c) {
            // Skip repositioning if the location was already loaded from prefs or shown before.
            if (hasBeenShown || locLoaded) { return; }
            super.setLocationRelativeTo(c);
        }

        @Override
        public void setVisible(boolean b0) {
            if (isVisible() == b0) { return; }
            if (!b0 && hasBeenShown) {
                // Persist position before the window actually hides (otherwise coords become 0,0).
                prefs.setPref(FPref.DRAFT_OVERLAY_LOC,
                        getX() + COORD_DELIM + getY() + COORD_DELIM +
                        getWidth() + COORD_DELIM + getHeight());
                prefs.save();
            }
            super.setVisible(b0);
            if (b0) {
                hasBeenShown = true;
            }
        }
    };

    public FDialog getWindow() {
        return window;
    }

    FDraftOverlay() {
        window.setTitle("Draft");
        window.setVisible(false);
        window.setBackground(FSkin.getColor(FSkin.Colors.CLR_ZEBRA));
        window.setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_BORDERS)));

        // Two-row layout: [pack info | timer] then [neighbor strip spanning both columns]
        window.setLayout(new MigLayout("insets 4, gap 0, wrap 2"));

        // Apply bold skin font and text color to all labels
        FSkin.SkinFont boldFont = FSkin.getBoldFont();
        FSkin.SkinColor textColor = FSkin.getColor(FSkin.Colors.CLR_TEXT);

        lblPackInfo.setFont(boldFont);
        lblTimer.setFont(boldFont);

        if (textColor != null) {
            lblPackInfo.setForeground(textColor);
            lblTimer.setForeground(textColor);
        }

        lblPackInfo.setHorizontalAlignment(SwingConstants.LEFT);
        lblTimer.setHorizontalAlignment(SwingConstants.RIGHT);

        lblPackInfo.setOpaque(false);
        lblTimer.setOpaque(false);
        pnlNeighbors.setOpaque(false);

        // Row 1: pack info on the left, timer on the right
        window.add(lblPackInfo,  "pushx, growx, gapleft 4");
        window.add(lblTimer,     "pushx, growx, gapright 4, al right");
        // Row 2: neighbor strip spans both columns
        window.add(pnlNeighbors, "span 2, pushx, growx, gapleft 4, gapright 4");

        // Load card back icon (scaled to small size)
        loadCardBackIcon();
    }

    /**
     * Called at draft start.
     *
     * @param mySeat       this player's seat index (0-based)
     * @param names        display names for all seats in pod order
     * @param aiFlags      parallel boolean array — true if that seat is AI
     * @param totalPacks   total number of packs in the draft
     */
    public void initDraft(int mySeat, String[] names, boolean[] aiFlags, int totalPacks) {
        SwingUtilities.invokeLater(() -> {
            this.mySeat     = mySeat;
            this.totalPacks = totalPacks;
            this.queueDepths = new int[names.length];

            int podSize = names.length;
            int leftIdx  = (mySeat - 1 + podSize) % podSize;
            int rightIdx = (mySeat + 1) % podSize;

            leftName  = names[leftIdx];
            rightName = names[rightIdx];
            leftAI    = aiFlags[leftIdx];
            rightAI   = aiFlags[rightIdx];

            waitingForPack = true;
            currentPack = 0;
            updateDisplay();
            show();
        });
    }

    /**
     * Called when a new pack arrives for this player to pick from.
     *
     * @param packNumber   1-based pack number
     * @param pickNumber   0-based pick number within the pack round
     * @param packSize     number of cards in the pack
     * @param timerSeconds seconds allowed to pick (0 = no timer)
     */
    public void onPackArrived(int packNumber, int pickNumber, int packSize, int timerSeconds) {
        SwingUtilities.invokeLater(() -> {
            currentPack    = packNumber;
            currentPick    = pickNumber + 1;
            currentPackSize = packSize;
            if (pickNumber == 0) {
                initialPackSize = packSize;
            }
            waitingForPack = false;
            timerDisabled  = (timerSeconds <= 0);
            // Pack direction: odd packs pass right, even packs pass left (conventional booster draft).
            passingRight   = (packNumber % 2 == 1);
            updateDisplay();
            if (timerSeconds > 0) {
                startCountdown(timerSeconds);
            }
        });
    }

    /**
     * Called when any seat in the pod picks a card.
     *
     * @param seatIndex   which seat picked
     * @param newDepths   updated queue-depth array (one entry per seat)
     */
    public void onSeatPicked(int seatIndex, int[] newDepths) {
        SwingUtilities.invokeLater(() -> {
            if (newDepths != null && newDepths.length == queueDepths.length) {
                System.arraycopy(newDepths, 0, queueDepths, 0, newDepths.length);
            }
            updateDisplay();
        });
    }

    /**
     * Called when THIS player submits a pick.
     * Stops the countdown and shows "Waiting for packs..." until the next pack arrives.
     */
    public void onPickSubmitted() {
        SwingUtilities.invokeLater(() -> {
            stopCountdown();
            waitingForPack = true;
            updateDisplay();
        });
    }

    /** Hides the overlay and clears draft state. Call at draft end. */
    public void reset() {
        SwingUtilities.invokeLater(() -> {
            stopCountdown();
            leftName = rightName = null;
            leftAI = rightAI = false;
            mySeat = 0;
            currentPack = totalPacks = 0;
            passingRight = false;
            queueDepths = new int[0];
            waitingForPack = false;
            lblPackInfo.setText("");
            lblTimer.setText("");
            pnlNeighbors.removeAll();
            hide();
        });
    }

    public void hide() {
        window.setVisible(false);
        OnlineMenu.draftItem.setState(false);
    }

    public void show() {
        if (!hasBeenShown) {
            hasBeenShown = true;
            loadLocation();
            window.getTitleBar().addMouseListener(new FMouseAdapter() {
                @Override
                public void onLeftDoubleClick(MouseEvent e) {
                    hide();
                }
            });
        }
        window.setVisible(true);
        OnlineMenu.draftItem.setState(true);
    }

    private void updateDisplay() {
        // Row 1 – pack info + pick number
        if (currentPack > 0 && totalPacks > 0) {
            String text = "Pack " + currentPack + " of " + totalPacks;
            if (currentPick > 0 && initialPackSize > 0) {
                text += "  \u2022  Pick " + currentPick + "/" + initialPackSize;
            }
            lblPackInfo.setText(text);
        } else {
            lblPackInfo.setText("Draft");
        }

        // Row 1 – timer
        if (waitingForPack) {
            lblTimer.setText("Waiting for packs...");
            lblTimer.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        } else if (countdownTimer != null && countdownTimer.isRunning()) {
            updateTimerLabel();
        } else if (timerDisabled) {
            lblTimer.setText("No timer");
            lblTimer.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        } else {
            lblTimer.setText("");
        }

        // Row 2 – neighbor strip
        if (leftName != null && rightName != null) {
            buildNeighborPanel();
        }

        window.revalidate();
        window.repaint();
    }

    private void startCountdown(int seconds) {
        stopCountdown();
        secondsRemaining = seconds;
        updateTimerLabel();
        countdownTimer = new Timer(1000, e -> {
            secondsRemaining--;
            if (secondsRemaining <= 0) {
                secondsRemaining = 0;
                stopCountdown();
            }
            updateTimerLabel();
        });
        countdownTimer.start();
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.stop();
            countdownTimer = null;
        }
    }

    private void updateTimerLabel() {
        int mins = secondsRemaining / 60;
        int secs = secondsRemaining % 60;
        lblTimer.setText(String.format("%d:%02d", mins, secs));

        // Color based on urgency
        Color timerColor;
        if (secondsRemaining <= 5) {
            timerColor = Color.RED;
        } else if (secondsRemaining <= 15) {
            timerColor = Color.YELLOW;
        } else {
            FSkin.SkinColor skinText = FSkin.getColor(FSkin.Colors.CLR_TEXT);
            timerColor = (skinText != null) ? skinText.getColor() : Color.WHITE;
        }
        lblTimer.setForeground(timerColor);
    }

    /**
     * Builds the neighbor display panel with text labels and card-back icons.
     *
     * Layout (passing right / odd packs):
     *   LeftName [queued packs]  →  [queued packs] YOU  →  RightName [queued packs]
     *
     * Layout (passing left / even packs):
     *   LeftName [queued packs]  ←  [queued packs] YOU  ←  RightName [queued packs]
     *
     * Pack icons appear on the INCOMING side of the seat they are waiting on.
     */
    private void buildNeighborPanel() {
        pnlNeighbors.removeAll();

        int podSize  = queueDepths.length;
        int leftIdx  = podSize > 0 ? (mySeat - 1 + podSize) % podSize : 0;
        int rightIdx = podSize > 0 ? (mySeat + 1) % podSize : 0;

        int myDepth    = podSize > 0 ? queueDepths[mySeat]   : 0;
        int leftDepth  = podSize > 0 ? queueDepths[leftIdx]  : 0;
        int rightDepth = podSize > 0 ? queueDepths[rightIdx] : 0;

        String leftLabel  = leftName  + (leftAI  ? " (AI)" : "");
        String rightLabel = rightName + (rightAI ? " (AI)" : "");
        String arrow = passingRight ? " \u2192 " : " \u2190 ";

        if (passingRight) {
            // Packs flow left → me → right
            pnlNeighbors.add(makeTextLabel(leftLabel));
            pnlNeighbors.add(makeTextLabel(arrow));
            addPackIcons(leftDepth);
            addPackIcons(myDepth);
            pnlNeighbors.add(makeTextLabel(" YOU" + arrow));
            addPackIcons(rightDepth);
            pnlNeighbors.add(makeTextLabel(" " + rightLabel));
        } else {
            // Packs flow right → me → left
            pnlNeighbors.add(makeTextLabel(leftLabel + " "));
            addPackIcons(leftDepth);
            pnlNeighbors.add(makeTextLabel(arrow + "YOU "));
            addPackIcons(myDepth);
            addPackIcons(rightDepth);
            pnlNeighbors.add(makeTextLabel(arrow + rightLabel));
        }

        pnlNeighbors.revalidate();
        pnlNeighbors.repaint();
    }

    private static void loadCardBackIcon() {
        if (cardBackIcon != null) return;
        try {
            BufferedImage img = ImageCache.getOriginalImage(
                    ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD), true, null);
            if (img != null) {
                Image scaled = img.getScaledInstance(14, 20, Image.SCALE_SMOOTH);
                cardBackIcon = new ImageIcon(scaled);
            }
        } catch (Exception e) {
            // Fallback: icon stays null, text "[P]" will be used
        }
    }

    private static final int MAX_PACK_ICONS = 2;

    private void addPackIcons(int depth) {
        int shown = Math.min(depth, MAX_PACK_ICONS);
        for (int i = 0; i < shown; i++) {
            if (cardBackIcon != null) {
                JLabel icon = new JLabel(cardBackIcon);
                icon.setOpaque(false);
                pnlNeighbors.add(icon);
            } else {
                pnlNeighbors.add(makeTextLabel("[P]"));
            }
        }
        if (depth > MAX_PACK_ICONS) {
            pnlNeighbors.add(makeTextLabel("+" + (depth - MAX_PACK_ICONS)));
        }
    }

    private JLabel makeTextLabel(String text) {
        FSkin.SkinnedLabel lbl = new FSkin.SkinnedLabel(text);
        lbl.setFont(FSkin.getBoldFont());
        FSkin.SkinColor color = FSkin.getColor(FSkin.Colors.CLR_TEXT);
        if (color != null) lbl.setForeground(color);
        lbl.setOpaque(false);
        return lbl;
    }

    private void loadLocation() {
        String value = prefs.getPref(FPref.DRAFT_OVERLAY_LOC);
        if (value.length() > 0) {
            String[] coords = value.split(COORD_DELIM);
            if (coords.length == 4) {
                try {
                    int x = Integer.parseInt(coords[0]);
                    int y = Integer.parseInt(coords[1]);
                    int w = Integer.parseInt(coords[2]);
                    int h = Integer.parseInt(coords[3]);

                    // Ensure the window center is on an accessible screen
                    int centerX = x + w / 2;
                    int centerY = y + h / 2;
                    Rectangle screenBounds = SDisplayUtil.getScreenBoundsForPoint(new Point(centerX, centerY));
                    if (centerX < screenBounds.x) {
                        x = screenBounds.x;
                    } else if (centerX > screenBounds.x + screenBounds.width) {
                        x = screenBounds.x + screenBounds.width - w;
                        if (x < screenBounds.x) { x = screenBounds.x; }
                    }
                    if (centerY < screenBounds.y) {
                        y = screenBounds.y;
                    } else if (centerY > screenBounds.y + screenBounds.height) {
                        y = screenBounds.y + screenBounds.height - h;
                        if (y < screenBounds.y) { y = screenBounds.y; }
                    }
                    window.setBounds(x, y, w, h);
                    locLoaded = true;
                    return;
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            prefs.setPref(FPref.DRAFT_OVERLAY_LOC, ""); // clear invalid value
            prefs.save();
        }

        // Fallback: place in top-left of the main Forge window, slightly inset
        FFrame mainFrame = Singletons.getView().getFrame();
        int x = mainFrame.getX() + 10;
        int y = mainFrame.getY() + 50;
        window.setBounds(x, y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }
}
