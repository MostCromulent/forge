package forge.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import forge.Singletons;
import forge.gui.framework.SDisplayUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
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

    // --- Swing components ---
    private final FSkin.SkinnedLabel lblPackInfo  = new FSkin.SkinnedLabel("");
    private final FSkin.SkinnedLabel lblTimer     = new FSkin.SkinnedLabel("");
    private final FSkin.SkinnedLabel lblNeighbors = new FSkin.SkinnedLabel("");

    // --- Draft state ---
    private String   leftName, rightName;
    private boolean  leftAI,   rightAI;
    private int      mySeat;
    private int      currentPack, totalPacks;
    private boolean  passingRight;
    private int[]    queueDepths = new int[0];

    /** Countdown timer (client-side fire-and-forget). */
    private javax.swing.Timer countdownTimer;
    private int               secondsRemaining;
    private boolean           waitingForPack;

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

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

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
        lblNeighbors.setFont(boldFont);

        if (textColor != null) {
            lblPackInfo.setForeground(textColor);
            lblTimer.setForeground(textColor);
            lblNeighbors.setForeground(textColor);
        }

        lblPackInfo.setHorizontalAlignment(SwingConstants.LEFT);
        lblTimer.setHorizontalAlignment(SwingConstants.RIGHT);
        lblNeighbors.setHorizontalAlignment(SwingConstants.CENTER);

        lblPackInfo.setOpaque(false);
        lblTimer.setOpaque(false);
        lblNeighbors.setOpaque(false);

        // Row 1: pack info on the left, timer on the right
        window.add(lblPackInfo,  "pushx, growx, gapleft 4");
        window.add(lblTimer,     "pushx, growx, gapright 4, al right");
        // Row 2: neighbor strip spans both columns
        window.add(lblNeighbors, "span 2, pushx, growx, gapleft 4, gapright 4");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called at draft start.
     *
     * @param mySeat       this player's seat index (0-based)
     * @param names        display names for all seats in pod order
     * @param aiFlags      parallel boolean array — true if that seat is AI
     * @param totalPacks   total number of packs in the draft
     */
    public void initDraft(int mySeat, String[] names, boolean[] aiFlags, int totalPacks) {
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
    }

    /**
     * Called when a new pack arrives for this player to pick from.
     *
     * @param packNumber   1-based pack number
     * @param timerSeconds seconds allowed to pick (0 = no timer)
     */
    public void onPackArrived(int packNumber, int timerSeconds) {
        SwingUtilities.invokeLater(() -> {
            currentPack    = packNumber;
            waitingForPack = false;
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
        lblNeighbors.setText("");
        hide();
    }

    public void hide() {
        window.setVisible(false);
    }

    public void show() {
        if (!hasBeenShown) {
            hasBeenShown = true;
            loadLocation();
            window.getTitleBar().addMouseListener(new FMouseAdapter() {
                @Override
                public void onLeftDoubleClick(MouseEvent e) {
                    window.setVisible(false); // hide on title-bar double-click
                }
            });
        }
        window.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void updateDisplay() {
        // Row 1 – pack info
        if (currentPack > 0 && totalPacks > 0) {
            lblPackInfo.setText("Pack " + currentPack + " of " + totalPacks);
        } else {
            lblPackInfo.setText("Draft");
        }

        // Row 1 – timer
        if (waitingForPack) {
            lblTimer.setText("Waiting for packs...");
            lblTimer.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        } else if (countdownTimer != null && countdownTimer.isRunning()) {
            updateTimerLabel();
        } else {
            lblTimer.setText("");
        }

        // Row 2 – neighbor strip
        if (leftName != null && rightName != null) {
            lblNeighbors.setText(buildNeighborText());
        }

        window.revalidate();
        window.repaint();
    }

    private void startCountdown(int seconds) {
        stopCountdown();
        secondsRemaining = seconds;
        updateTimerLabel();
        countdownTimer = new javax.swing.Timer(1000, e -> {
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
        lblTimer.setText(String.format("\u23F1 %d:%02d", mins, secs));

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
     * Builds the HTML neighbor display string.
     *
     * Layout (passing right / odd packs):
     *   LeftName [queued packs]  →  [queued packs] YOU  →  RightName [queued packs]
     *
     * Layout (passing left / even packs):
     *   LeftName [queued packs]  ←  [queued packs] YOU  ←  RightName [queued packs]
     *
     * Pack icons appear on the INCOMING side of the seat they are waiting on.
     */
    private String buildNeighborText() {
        int podSize  = queueDepths.length;
        int leftIdx  = podSize > 0 ? (mySeat - 1 + podSize) % podSize : 0;
        int rightIdx = podSize > 0 ? (mySeat + 1) % podSize : 0;

        int myDepth    = podSize > 0 ? queueDepths[mySeat]   : 0;
        int leftDepth  = podSize > 0 ? queueDepths[leftIdx]  : 0;
        int rightDepth = podSize > 0 ? queueDepths[rightIdx] : 0;

        String leftLabel  = leftName  + (leftAI  ? " (AI)" : "");
        String rightLabel = rightName + (rightAI ? " (AI)" : "");
        String myLabel    = "YOU";

        String arrow = passingRight ? "\u2192" : "\u2190";

        // Pack icons: [P] repeated by depth; 0 depth shows nothing (pick submitted)
        String leftPacks  = packIcons(leftDepth);
        String myPacks    = packIcons(myDepth);
        String rightPacks = packIcons(rightDepth);

        if (passingRight) {
            // Packs travel: left → you → right
            // Incoming to left = from the seat further left (not shown), outgoing shown on right of left
            // Incoming to you  = from left (shown right of leftLabel)
            // Incoming to right = from you (shown left of rightLabel, right of arrow)
            return leftLabel + " " + leftPacks
                    + "  " + arrow + "  "
                    + myPacks + " " + myLabel
                    + "  " + arrow + "  "
                    + rightPacks + " " + rightLabel;
        } else {
            // Packs travel: right → you → left
            return leftLabel + " " + leftPacks
                    + "  " + arrow + "  "
                    + myLabel + " " + myPacks
                    + "  " + arrow + "  "
                    + rightLabel + " " + rightPacks;
        }
    }

    /** Returns a string of [P] icons, one per pack queued. Empty string when depth is 0. */
    private static String packIcons(int depth) {
        if (depth <= 0) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) { sb.append(' '); }
            sb.append("[P]");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Position persistence  (same pattern as FNetOverlay)
    // -------------------------------------------------------------------------

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
