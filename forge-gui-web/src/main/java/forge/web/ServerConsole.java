package forge.web;

import forge.gamemodes.net.server.FServerManager;
import forge.gui.interfaces.IProgressBar;
import forge.util.Localizer;
import org.tinylog.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The desktop window that starts and stops the server, without which a player who closes their tab has no sign Forge still runs. */
final class ServerConsole implements IProgressBar {
    /** Enough to see what just happened without holding a whole session's output. */
    private static final int MAX_LINES = 300;
    /** Card loading prints tens of thousands of lines, so the text is fed on a timer rather than per line. */
    private static final int FLUSH_MILLIS = 250;

    /** Running is a filled lamp, stopped an empty ring: the shape carries it, so the colour need not. */
    private static final Color LIT = new Color(0x3f, 0xb9, 0x50);
    private static final Color DARK = new Color(0xd0, 0x57, 0x4e);
    private static final Color LINK = new Color(0x1f, 0x5f, 0xc4);

    private final WebGuiBase ui;
    private final Runnable onQuit;
    private final StringBuilder pending = new StringBuilder();
    private final Light light = new Light();
    private final JLabel state = new JLabel(Localizer.getInstance().getMessage("lblWebConsoleStarting"));
    private final JPanel links = new JPanel();
    private final JLabel copied = new JLabel();
    private final Timer copiedFade = new Timer(2000, e -> copied.setText(""));
    private final JProgressBar progress = new JProgressBar();
    private final JCheckBox quitWhenEmpty = new JCheckBox(Localizer.getInstance().getMessage("lblWebConsoleQuitWhenEmpty"), true);
    private final JCheckBox forwardPort = new JCheckBox(Localizer.getInstance().getMessage("lblWebConsoleForwardPort"));
    private final JLabel forwardState = new JLabel();
    private final TrafficGraph graph = new TrafficGraph();
    private final StatsBox stats = new StatsBox();
    private final JButton imagesButton = new JButton(Localizer.getInstance().getMessage("lblWebImagesButton"));
    private final CardImageDownloads images = new CardImageDownloads(label -> SwingUtilities.invokeLater(() -> imagesButton.setText(label)));
    private volatile ServerTraffic traffic;
    private JFrame frame;
    private JTextPane text;
    /** The colour of the last error or warning written, so the stack trace under it is coloured with it. */
    private SimpleAttributeSet carried = PLAIN;
    private JButton browse;
    private JButton startStop;
    private WebService service;
    private volatile boolean quitting;

    private ServerConsole(final WebGuiBase ui, final Runnable onQuit) {
        this.ui = ui;
        this.onQuit = onQuit;
    }

    /** Opens the console, or returns null when there is no display or the flag turns it off. */
    static ServerConsole open(final WebGuiBase ui, final Runnable onQuit) {
        if (Boolean.getBoolean("forge.web.noConsole") || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final ServerConsole console = new ServerConsole(ui, onQuit);
        try {
            SwingUtilities.invokeAndWait(console::build);
        } catch (final Exception e) {
            Logger.warn(e, "Could not open the console");
            return null;
        }
        console.captureOutput();
        return console;
    }

    /** Names the step of the start-up that is running, so the bar covers the whole wait and not just the cards. */
    void starting(final String what) {
        SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(true);
            progress.setString(what);
        });
    }

    /** The server is up, and this is what the buttons drive from here on. */
    void attach(final WebService driven) {
        service = driven;
        SwingUtilities.invokeLater(() -> {
            startStop.setEnabled(true);
            quitWhenEmpty.setEnabled(true);
            forwardPort.setSelected(driven.forwardPort());
            forwardPort.setEnabled(true);
        });
        images.ready();
        SwingUtilities.invokeLater(() -> imagesButton.setEnabled(true));
        driven.onForwarding(this::forwarding);
        running();
    }

    /** The port is bound: the lamp is lit and the links are worth copying. */
    private void running() {
        traffic = service.traffic();
        SwingUtilities.invokeLater(() -> {
            light.lit(true);
            state.setText(Localizer.getInstance().getMessage("lblWebConsoleRunning"));
            startStop.setText(Localizer.getInstance().getMessage("lblWebConsoleStopServer"));
            browse.setEnabled(true);
            progress.setIndeterminate(false);
            progress.setValue(progress.getMaximum());
            progress.setString(Localizer.getInstance().getMessage("lblReady"));
        });
        inBackground("ForgeAddresses", this::findAddresses);
    }

    /** For work that waits on the network or the router, which the event thread must not do. */
    private static void inBackground(final String name, final Runnable work) {
        final Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }

    /** Shows where asking the router stands. */
    private void forwarding(final WebService.Forwarding now) {
        SwingUtilities.invokeLater(() -> {
            forwardState.setForeground(now == WebService.Forwarding.REFUSED ? DARK : UIManager.getColor("Label.foreground"));
            forwardState.setText(switch (now) {
                case OFF -> "";
                case ASKING -> Localizer.getInstance().getMessage("lblWebConsoleAskingRouter");
                case FORWARDED -> Localizer.getInstance().getMessage("lblWebConsoleForwarded", service.port());
                case REFUSED -> Localizer.getInstance().getMessage("lblWebConsoleRefused", service.port());
            });
        });
    }

    /** The port is closed: there is nothing to link to until it is started again. */
    private void stopped() {
        traffic = null;
        SwingUtilities.invokeLater(() -> {
            light.lit(false);
            state.setText(Localizer.getInstance().getMessage("lblWebConsoleStopped"));
            startStop.setText(Localizer.getInstance().getMessage("lblWebConsoleStartServer"));
            browse.setEnabled(false);
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString(Localizer.getInstance().getMessage("lblWebConsoleStopped"));
            showLinks(List.of());
        });
    }

    /** Start or stop, off the event thread because binding a port and closing one both take their time. */
    private void toggle() {
        final boolean up = service.running();
        startStop.setEnabled(false);
        starting(Localizer.getInstance().getMessage(up ? "lblWebConsoleStoppingServer" : "lblWebConsoleStartingServer"));
        inBackground("ForgeServerControl", () -> {
            try {
                if (up) {
                    service.stop();
                } else {
                    service.start();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final RuntimeException e) {
                Logger.error(e, "Could not {} the server", up ? "stop" : "start");
            }
            if (service.running()) {
                running();
            } else {
                stopped();
            }
            SwingUtilities.invokeLater(() -> startStop.setEnabled(true));
        });
    }

    /** Found off the event thread, because the address the internet sees is a web request of its own. */
    private void findAddresses() {
        final List<Invite> found = new ArrayList<>();
        for (final Map.Entry<String, String> local : FServerManager.getAllLocalAddresses().entrySet()) {
            found.add(new Invite(local.getKey(), local.getValue(), service.inviteUrl(local.getValue())));
        }
        final String external = FServerManager.getExternalAddress();
        if (external != null) {
            found.add(new Invite(Localizer.getInstance().getMessage("lblWebConsoleInternet"), external, service.inviteUrl(external)));
        }
        SwingUtilities.invokeLater(() -> showLinks(found));
    }

    private record Invite(String caption, String address, String url) { }

    /** Each address a guest could use, which copies that address's full link when clicked. */
    private void showLinks(final List<Invite> found) {
        links.removeAll();
        final JLabel name = new JLabel(Localizer.getInstance().getMessage("lblWebMatchBarInvite"));
        name.setPreferredSize(new Dimension(80, name.getPreferredSize().height));
        name.setMaximumSize(name.getPreferredSize());
        links.add(name);
        if (found.isEmpty()) {
            links.add(new JLabel("—"));
        }
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) {
                links.add(new JLabel("  ·  "));
            }
            links.add(inviteLink(found.get(i)));
        }
        links.add(Box.createHorizontalStrut(12));
        links.add(copied);
        links.add(Box.createHorizontalGlue());
        links.revalidate();
        links.repaint();
    }

    private JButton inviteLink(final Invite invite) {
        final JButton link = new JButton(invite.caption() + " " + invite.address());
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setFocusPainted(false);
        link.setMargin(new Insets(0, 0, 0, 0));
        link.setForeground(LINK);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(Localizer.getInstance().getMessage("lblWebConsoleCopyLink", invite.url()));
        link.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(invite.url()), null);
            copied.setText(Localizer.getInstance().getMessage("lblWebConsoleLinkCopied", invite.caption()));
            copiedFade.restart();
        });
        return link;
    }

    private void build() {
        final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

        links.setLayout(new BoxLayout(links, BoxLayout.LINE_AXIS));
        links.setAlignmentX(0f);
        copiedFade.setRepeats(false);
        showLinks(List.of());

        progress.setStringPainted(true);
        progress.setString(Localizer.getInstance().getMessage("lblWebConsoleStartingForge"));
        progress.setIndeterminate(true);
        progress.setAlignmentX(0f);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        browse = button(Localizer.getInstance().getMessage("lblWebConsoleOpenBrowser"), () -> WebMain.openBrowser(service.url(), ui));
        browse.setEnabled(false);
        startStop = button(Localizer.getInstance().getMessage("lblWebConsoleStopServer"), this::toggle);
        startStop.setEnabled(false);

        // The lamp and the word say the same thing twice, because a lamp alone is a colour and not everyone reads it
        final JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.LINE_AXIS));
        status.setAlignmentX(0f);
        status.add(light);
        status.add(Box.createHorizontalStrut(8));
        status.add(state);
        status.add(Box.createHorizontalGlue());
        imagesButton.addActionListener(e -> images.show(frame));
        imagesButton.setEnabled(false);
        status.add(imagesButton);
        status.add(Box.createHorizontalStrut(8));
        status.add(browse);
        status.add(Box.createHorizontalStrut(8));
        status.add(startStop);

        quitWhenEmpty.setAlignmentX(0f);
        quitWhenEmpty.setEnabled(false);
        quitWhenEmpty.addActionListener(e -> service.quitWhenEmpty(quitWhenEmpty.isSelected()));
        forwardPort.setEnabled(false);
        forwardPort.addActionListener(e -> {
            final boolean on = forwardPort.isSelected();
            inBackground("ForgePortForward", () -> service.forwardPort(on));
        });
        final JPanel forwardRow = new JPanel();
        forwardRow.setLayout(new BoxLayout(forwardRow, BoxLayout.LINE_AXIS));
        forwardRow.setAlignmentX(0f);
        forwardRow.add(forwardPort);
        forwardRow.add(Box.createHorizontalStrut(12));
        forwardRow.add(forwardState);
        forwardRow.add(Box.createHorizontalGlue());

        final JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.PAGE_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        head.add(status);
        head.add(Box.createVerticalStrut(14));
        head.add(links);
        head.add(Box.createVerticalStrut(14));
        head.add(progress);
        head.add(Box.createVerticalStrut(10));
        final JPanel trafficRow = new JPanel();
        trafficRow.setLayout(new BoxLayout(trafficRow, BoxLayout.LINE_AXIS));
        trafficRow.setAlignmentX(0f);
        trafficRow.add(graph);
        trafficRow.add(Box.createHorizontalStrut(10));
        trafficRow.add(stats);
        head.add(trafficRow);

        final JPanel foot = new JPanel();
        foot.setLayout(new BoxLayout(foot, BoxLayout.PAGE_AXIS));
        foot.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        foot.add(quitWhenEmpty);
        foot.add(forwardRow);

        // A styled pane so errors and warnings can stand out, and it wraps long lines because a stack trace is wider than the window
        text = new JTextPane();
        text.setEditable(false);
        text.setFont(mono);
        text.setBackground(new Color(0x10, 0x14, 0x1c));
        text.setForeground(new Color(0xc8, 0xd1, 0xdb));
        text.setMargin(new Insets(6, 8, 6, 8));

        frame = new JFrame(Localizer.getInstance().getMessage("lblWebConsoleTitle"));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                quit();
            }
        });
        frame.getContentPane().add(head, BorderLayout.NORTH);
        final JPanel log = new JPanel(new BorderLayout());
        log.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        log.add(new JScrollPane(text));
        final String copyLogLabel = Localizer.getInstance().getMessage("lblWebConsoleCopyLog");
        final JButton copyLog = new JButton(copyLogLabel);
        final Timer copyLogReset = new Timer(2000, e -> copyLog.setText(copyLogLabel));
        copyLogReset.setRepeats(false);
        copyLog.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null);
            copyLog.setText(Localizer.getInstance().getMessage("lblWebMatchBarCopied"));
            copyLogReset.restart();
        });
        final JPanel logActions = new JPanel();
        logActions.setLayout(new BoxLayout(logActions, BoxLayout.LINE_AXIS));
        logActions.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        logActions.add(Box.createHorizontalGlue());
        logActions.add(copyLog);
        log.add(logActions, BorderLayout.SOUTH);
        frame.getContentPane().add(log, BorderLayout.CENTER);
        frame.getContentPane().add(foot, BorderLayout.SOUTH);
        // Wide enough for the port option with the router's refusal beside it, the longest row the console shows
        frame.setSize(new Dimension(780, 620));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(FLUSH_MILLIS, e -> flush()).start();
        new Timer(TrafficGraph.SAMPLE_MILLIS, e -> {
            final ServerTraffic now = traffic;
            graph.sample(now);
            stats.show(now, service == null ? 0 : service.playersHere());
        }).start();
    }

    private static JButton button(final String label, final Runnable action) {
        final JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A lamp, filled while the server is up and an empty ring while it is not. */
    private static final class Light extends JComponent {
        private static final int SIZE = 11;
        private boolean on;

        Light() {
            final Dimension size = new Dimension(SIZE, SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        void lit(final boolean value) {
            on = value;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(on ? LIT : DARK);
            if (on) {
                g2.fillOval(0, 0, SIZE - 1, SIZE - 1);
            } else {
                g2.drawOval(0, 0, SIZE - 1, SIZE - 1);
            }
            g2.dispose();
        }
    }

    /** The last five minutes of bytes through the port, with what was sent stacked by kind and what was received as a dashed line. */
    private static final class TrafficGraph extends JComponent {
        static final int SAMPLE_MILLIS = 1000;
        static final int HEIGHT = 130;
        static final Color BACKGROUND = new Color(0x10, 0x14, 0x1c);
        static final Color LABEL = new Color(0xc8, 0xd1, 0xdb);
        /** Indexed by {@link ServerTraffic.Kind}, and stacked in that order from the bottom. */
        static final String[] KIND_NAMES = {Localizer.getInstance().getMessage("lblGame"),
                Localizer.getInstance().getMessage("lblWebSleevesCardArt"), Localizer.getInstance().getMessage("lblAudio"),
                Localizer.getInstance().getMessage("lblWebConsoleKindPage")};
        static final Color[] KIND_COLOURS = {new Color(0xf5, 0xc4, 0x51), new Color(0x4f, 0x86, 0xe8),
                new Color(0xa9, 0xc4, 0xff), new Color(0x4a, 0x52, 0x60)};
        private static final ServerTraffic.Kind[] KINDS = ServerTraffic.Kind.values();
        private static final int SAMPLES = 300;
        /** The floor of the scale, so a server with nobody on it does not magnify a few bytes into peaks. */
        private static final long SMALLEST_SCALE = 1024;
        private static final Color GRID = new Color(0x2a, 0x31, 0x3c);
        private static final Color IN = new Color(0xe8, 0xee, 0xf4);
        private static final BasicStroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10f, new float[] {5f, 3f}, 0f);

        private final long[] in = new long[SAMPLES];
        private final long[][] out = new long[KINDS.length][SAMPLES];
        /** Where the next sample goes, which is also the oldest one still shown. */
        private int next;
        private ServerTraffic counter;
        private long receivedBefore;
        private final long[] sentBefore = new long[KINDS.length];

        TrafficGraph() {
            setPreferredSize(new Dimension(400, HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
            setAlignmentX(0f);
        }

        /** The counters only ever grow, so a sample is the difference from the last; a new server starts from zero. */
        void sample(final ServerTraffic now) {
            final long received = now == null ? 0 : now.received();
            final long[] sent = new long[KINDS.length];
            for (final ServerTraffic.Kind kind : KINDS) {
                sent[kind.ordinal()] = now == null ? 0 : now.sent(kind);
            }
            if (now != counter) {
                counter = now;
                receivedBefore = received;
                System.arraycopy(sent, 0, sentBefore, 0, sent.length);
            }
            in[next] = received - receivedBefore;
            receivedBefore = received;
            for (int k = 0; k < sent.length; k++) {
                out[k][next] = sent[k] - sentBefore[k];
                sentBefore[k] = sent[k];
            }
            next = (next + 1) % SAMPLES;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final int w = getWidth();
            final int h = getHeight();
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, w, h);

            // stacked[k][i] is everything the first k + 1 kinds sent in sample i, oldest sample first
            final long[][] stacked = new long[KINDS.length][SAMPLES];
            final long[] received = new long[SAMPLES];
            long top = SMALLEST_SCALE;
            for (int i = 0; i < SAMPLES; i++) {
                final int at = (next + i) % SAMPLES;
                long sum = 0;
                for (int k = 0; k < KINDS.length; k++) {
                    sum += out[k][at];
                    stacked[k][i] = sum;
                }
                received[i] = in[at];
                top = Math.max(top, Math.max(sum, in[at]));
            }
            final int plotTop = 38;
            final int plotHeight = h - plotTop - 4;
            g2.setColor(GRID);
            g2.drawLine(0, plotTop, w, plotTop);
            g2.drawLine(0, h - 4, w, h - 4);
            final int[] xs = xs();
            // The tallest layer first, so each lower one is painted over the part of it that is not its own
            for (int k = KINDS.length - 1; k >= 0; k--) {
                g2.setColor(KIND_COLOURS[k]);
                g2.fillPolygon(xs, ys(stacked[k], top, plotTop, plotHeight), SAMPLES + 2);
            }
            g2.setColor(IN);
            g2.setStroke(DASHED);
            g2.drawPolyline(xs, ys(received, top, plotTop, plotHeight), SAMPLES);

            g2.setFont(getFont().deriveFont(11f));
            g2.setColor(LABEL);
            g2.drawString(Localizer.getInstance().getMessage("lblWebConsoleInOut", rate(received[SAMPLES - 1]), rate(stacked[KINDS.length - 1][SAMPLES - 1])), 8, 14);
            final String scale = Localizer.getInstance().getMessage("lblWebConsoleScale", rate(top));
            g2.drawString(scale, w - 8 - g2.getFontMetrics().stringWidth(scale), 14);
            int x = 8;
            for (int k = 0; k < KINDS.length; k++) {
                x = swatch(g2, x, 30, KIND_COLOURS[k], KIND_NAMES[k]) + 14;
            }
            g2.setColor(IN);
            g2.setStroke(DASHED);
            g2.drawLine(x, 26, x + 16, 26);
            g2.setColor(LABEL);
            g2.drawString(Localizer.getInstance().getMessage("lblWebConsoleReceived"), x + 22, 30);
            g2.dispose();
        }

        /** A filled square in a kind's colour and its name; returns where the next entry can start. */
        static int swatch(final Graphics2D g2, final int x, final int baseline, final Color colour, final String name) {
            g2.setColor(colour);
            g2.fillRect(x, baseline - 9, 9, 9);
            g2.setColor(LABEL);
            g2.drawString(name, x + 14, baseline);
            return x + 14 + g2.getFontMetrics().stringWidth(name);
        }

        /** Each sample's x, then the plot's two bottom corners, so the same points close an area under a line. */
        private int[] xs() {
            final int[] xs = new int[SAMPLES + 2];
            for (int i = 0; i < SAMPLES; i++) {
                xs[i] = i * (getWidth() - 1) / (SAMPLES - 1);
            }
            xs[SAMPLES] = xs[SAMPLES - 1];
            xs[SAMPLES + 1] = xs[0];
            return xs;
        }

        /** Each sample's y, then the plot's floor twice, to go with xs. */
        private static int[] ys(final long[] values, final long top, final int plotTop, final int plotHeight) {
            final int[] ys = new int[SAMPLES + 2];
            for (int i = 0; i < SAMPLES; i++) {
                ys[i] = plotTop + plotHeight - (int) (values[i] * plotHeight / top);
            }
            ys[SAMPLES] = plotTop + plotHeight;
            ys[SAMPLES + 1] = plotTop + plotHeight;
            return ys;
        }

        private static String rate(final long bytesPerSecond) {
            return bytes(bytesPerSecond) + "/s";
        }
    }

    /** Beside the graph, in its colours: how long the server has been up, who is on it, and every byte so far. */
    private static final class StatsBox extends JComponent {
        private static final int WIDTH = 210;
        private static final String[] NAMES = {Localizer.getInstance().getMessage("lblWebConsoleUp"),
                Localizer.getInstance().getMessage("lblPlayers"), Localizer.getInstance().getMessage("lblWebConsoleReceived")};
        /** Up, players and received, then what was sent of each kind, then everything sent. */
        private String[] values = new String[NAMES.length + TrafficGraph.KIND_NAMES.length + 1];

        StatsBox() {
            final Dimension size = new Dimension(WIDTH, TrafficGraph.HEIGHT);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            show(null, 0);
        }

        void show(final ServerTraffic traffic, final int players) {
            final String[] now = new String[values.length];
            now[0] = traffic == null ? "—" : uptime(System.currentTimeMillis() - traffic.started());
            now[1] = String.valueOf(players);
            now[2] = traffic == null ? "—" : bytes(traffic.received());
            long sent = 0;
            for (final ServerTraffic.Kind kind : ServerTraffic.Kind.values()) {
                final long bytes = traffic == null ? 0 : traffic.sent(kind);
                now[NAMES.length + kind.ordinal()] = traffic == null ? "—" : bytes(bytes);
                sent += bytes;
            }
            now[now.length - 1] = traffic == null ? "—" : bytes(sent);
            values = now;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(TrafficGraph.BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(getFont().deriveFont(11f));
            for (int i = 0; i < values.length; i++) {
                final int baseline = 16 + i * 15;
                g2.setColor(TrafficGraph.LABEL);
                if (i < NAMES.length) {
                    g2.drawString(NAMES[i], 10, baseline);
                } else if (i == values.length - 1) {
                    g2.drawString(Localizer.getInstance().getMessage("lblWebConsoleSentTotal"), 24, baseline);
                } else {
                    final int k = i - NAMES.length;
                    TrafficGraph.swatch(g2, 10, baseline, TrafficGraph.KIND_COLOURS[k], Localizer.getInstance().getMessage("lblWebConsoleSentKind", TrafficGraph.KIND_NAMES[k]));
                }
                g2.drawString(values[i], getWidth() - 10 - g2.getFontMetrics().stringWidth(values[i]), baseline);
            }
            g2.dispose();
        }

        private static String uptime(final long millis) {
            final long seconds = millis / 1000;
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
    }

    private static String bytes(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void quit() {
        if (quitting) {
            return;
        }
        quitting = true;
        onQuit.run();
    }

    // --- the card reader's progress, shown on the bar -------------------------------------------------

    @Override
    public void setDescription(final String s0) {
        SwingUtilities.invokeLater(() -> progress.setString(s0));
    }

    @Override
    public void setValue(final int value) {
        SwingUtilities.invokeLater(() -> progress.setValue(value));
    }

    @Override
    public void reset() {
        SwingUtilities.invokeLater(() -> progress.setValue(0));
    }

    @Override
    public void setShowETA(final boolean b0) {
    }

    @Override
    public void setShowCount(final boolean b0) {
    }

    @Override
    public void setPercentMode(final boolean percentMode0) {
    }

    @Override
    public int getMaximum() {
        return progress.getMaximum();
    }

    @Override
    public void setMaximum(final int maximum) {
        SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(maximum <= 0);
            progress.setMaximum(Math.max(1, maximum));
        });
    }

    // --- the log --------------------------------------------------------------------------------------

    /** Everything the process prints also lands here, which is the log a player can actually see. */
    private void captureOutput() {
        System.setOut(tee(System.out));
        System.setErr(tee(System.err));
    }

    private PrintStream tee(final PrintStream original) {
        // var keeps the anonymous type, so drain() is callable without ByteArrayOutputStream's throwing flush
        final var copyOf = new ByteArrayOutputStream() {
            void drain() {
                final String chunk = toString(StandardCharsets.UTF_8);
                reset();
                if (!chunk.isEmpty()) {
                    synchronized (pending) {
                        pending.append(chunk);
                    }
                }
            }
        };
        return new PrintStream(new OutputStream() {
            @Override
            public void write(final int b) {
                original.write(b);
                copyOf.write(b);
                if (b == '\n') {
                    copyOf.drain();
                }
            }

            // Card loading prints tens of thousands of lines, and a byte at a time is too slow for that
            @Override
            public void write(final byte[] bytes, final int off, final int len) {
                original.write(bytes, off, len);
                copyOf.write(bytes, off, len);
                copyOf.drain();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void flush() {
        final String chunk;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            chunk = pending.toString();
            pending.setLength(0);
        }
        final StyledDocument doc = text.getStyledDocument();
        try {
            for (final String line : chunk.split("(?<=\n)")) {
                doc.insertString(doc.getLength(), line, styleOf(line));
            }
        } catch (final BadLocationException e) {
            text.setText(chunk);
        }
        trim();
        text.setCaretPosition(doc.getLength());
    }

    // Red for errors and the stack traces under them, amber for warnings; the rest in the log's own colour
    private static final SimpleAttributeSet PLAIN = new SimpleAttributeSet();
    private static final SimpleAttributeSet ERROR = colour(new Color(0xff, 0x6b, 0x5c));
    private static final SimpleAttributeSet WARNING = colour(new Color(0xf2, 0xc3, 0x44));

    private static SimpleAttributeSet colour(final Color c) {
        final SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, c);
        return set;
    }

    /** A stack trace line carries on the colour of the error or warning above it, which tinylog marks as [ERROR] or [WARN ]. */
    private SimpleAttributeSet styleOf(final String line) {
        if (line.contains("[ERROR]")) {
            return carried = ERROR;
        }
        if (line.contains("[WARN")) {
            return carried = WARNING;
        }
        final String bare = line.strip();
        final boolean thrown = bare.matches("^[\\w.$]+(Exception|Error)(:.*)?$");
        final boolean trace = thrown || line.startsWith("\t") || line.startsWith(" ") || bare.startsWith("Caused by")
                || bare.startsWith("at ") || bare.startsWith("...");
        if (carried != PLAIN && (trace || bare.isEmpty())) {
            return carried;
        }
        return carried = thrown ? ERROR : PLAIN;
    }

    private void trim() {
        final StyledDocument doc = text.getStyledDocument();
        final Element root = doc.getDefaultRootElement();
        final int lines = root.getElementCount();
        if (lines <= MAX_LINES) {
            return;
        }
        try {
            doc.remove(0, root.getElement(lines - MAX_LINES - 1).getEndOffset());
        } catch (final BadLocationException e) {
            text.setText("");
        }
    }

    void close() {
        quitting = true;
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }
}
