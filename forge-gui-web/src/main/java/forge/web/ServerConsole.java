package forge.web;

import forge.gamemodes.net.server.FServerManager;
import forge.gui.interfaces.IProgressBar;
import io.netty.handler.traffic.TrafficCounter;
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
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
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

/**
 * The desktop window that starts the server, shows what it is doing and stops it. The browser is the whole
 * of the game, so without this a player who closes their tab has no sign the process is still there.
 *
 * <p>Three things share this package and are easy to confuse. The <em>server</em> is the port players reach,
 * which this window can stop and start again. The <em>console</em> is this window. The <em>browser</em> is the
 * page a player actually plays in.
 *
 * <p>Closing the console ends Forge; stopping the server only closes the port. A machine with no display, or
 * one started with {@code -Dforge.web.noConsole}, gets none of this and behaves as it did before.
 */
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
    private final JLabel state = new JLabel("Starting");
    private final JPanel links = new JPanel();
    private final JLabel copied = new JLabel();
    private final Timer copiedFade = new Timer(2000, e -> copied.setText(""));
    private final JProgressBar progress = new JProgressBar();
    private final JCheckBox quitWhenEmpty = new JCheckBox("Quit when the last player leaves", true);
    private final JCheckBox forwardPort = new JCheckBox("Open the port on the router, for players on the internet");
    private final JLabel forwardState = new JLabel();
    private final TrafficGraph graph = new TrafficGraph();
    private final StatsBox stats = new StatsBox();
    private volatile TrafficCounter traffic;
    private JFrame frame;
    private JTextArea text;
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
        driven.onForwarding(this::forwarding);
        running();
    }

    /** The port is bound: the lamp is lit and the links are worth copying. */
    private void running() {
        traffic = service.traffic();
        SwingUtilities.invokeLater(() -> {
            light.lit(true);
            state.setText("Running");
            startStop.setText("Stop server");
            browse.setEnabled(true);
            progress.setIndeterminate(false);
            progress.setValue(progress.getMaximum());
            progress.setString("Ready");
        });
        lookUpAddresses();
    }

    private void lookUpAddresses() {
        inBackground("ForgeAddresses", this::findAddresses);
    }

    /** For work that waits on the network or the router, which the event thread must not do. */
    private static void inBackground(final String name, final Runnable work) {
        final Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }

    /** Shows where asking the router stands, and relists the links, since the internet one depends on it. */
    private void forwarding(final WebService.Forwarding now) {
        SwingUtilities.invokeLater(() -> {
            forwardState.setForeground(now == WebService.Forwarding.REFUSED ? DARK : UIManager.getColor("Label.foreground"));
            forwardState.setText(switch (now) {
                case OFF -> "";
                case ASKING -> "Asking the router…";
                case FORWARDED -> "The router is forwarding port " + service.port() + ".";
                case REFUSED -> "Refused. Turn on UPnP on the router, or forward port "
                        + service.port() + " by hand.";
            });
        });
        if (now == WebService.Forwarding.FORWARDED || now == WebService.Forwarding.REFUSED) {
            lookUpAddresses();
        }
    }

    /** The port is closed: there is nothing to link to until it is started again. */
    private void stopped() {
        traffic = null;
        SwingUtilities.invokeLater(() -> {
            light.lit(false);
            state.setText("Stopped");
            startStop.setText("Start server");
            browse.setEnabled(false);
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString("Stopped");
            showLinks(List.of());
        });
    }

    /** Start or stop, off the event thread because binding a port and closing one both take their time. */
    private void toggle() {
        final boolean up = service.running();
        startStop.setEnabled(false);
        starting(up ? "Stopping the server" : "Starting the server");
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

    /**
     * Found off the event thread, because the address the internet sees is a web request of its own. Every link
     * comes from the server, so what the console shows cannot drift from what a player is actually given.
     */
    private void findAddresses() {
        final List<Invite> found = new ArrayList<>();
        for (final Map.Entry<String, String> local : FServerManager.getAllLocalAddresses().entrySet()) {
            found.add(new Invite(local.getKey(), local.getValue(), service.inviteUrl(local.getValue())));
        }
        final String external = FServerManager.getExternalAddress();
        if (external != null) {
            found.add(new Invite("Internet", external, service.inviteUrl(external)));
        }
        SwingUtilities.invokeLater(() -> showLinks(found));
    }

    private record Invite(String caption, String address, String url) { }

    /** Each address a guest could use, which copies that address's full link when clicked. */
    private void showLinks(final List<Invite> found) {
        links.removeAll();
        final JLabel name = new JLabel("Invite");
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
        link.setToolTipText("Copy " + invite.url());
        link.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(invite.url()), null);
            copied.setText(invite.caption() + " link copied");
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
        progress.setString("Starting Forge");
        progress.setIndeterminate(true);
        progress.setAlignmentX(0f);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        browse = button("Open browser", () -> WebMain.openBrowser(service.url(), ui));
        browse.setEnabled(false);
        startStop = button("Stop server", this::toggle);
        startStop.setEnabled(false);

        // The lamp and the word say the same thing twice, because a lamp alone is a colour and not everyone reads it
        final JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.LINE_AXIS));
        status.setAlignmentX(0f);
        status.add(light);
        status.add(Box.createHorizontalStrut(8));
        status.add(state);
        status.add(Box.createHorizontalGlue());
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

        text = new JTextArea();
        text.setEditable(false);
        text.setFont(mono);
        // A stack trace is wider than any window worth opening, so the log wraps rather than scrolling sideways
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(new Color(0x10, 0x14, 0x1c));
        text.setForeground(new Color(0xc8, 0xd1, 0xdb));
        text.setMargin(new Insets(6, 8, 6, 8));

        frame = new JFrame("Forge server");
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
        frame.getContentPane().add(log, BorderLayout.CENTER);
        frame.getContentPane().add(foot, BorderLayout.SOUTH);
        // Wide enough for the port option with the router's refusal beside it, the longest row the console shows
        frame.setSize(new Dimension(780, 620));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(FLUSH_MILLIS, e -> flush()).start();
        new Timer(TrafficGraph.SAMPLE_MILLIS, e -> {
            final TrafficCounter now = traffic;
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

    /**
     * The last minute of bytes in and out of the port. The two lines differ in lightness and in dash as well as in
     * colour, and the legend gives the figures in words, so neither depends on telling the colours apart.
     */
    private static final class TrafficGraph extends JComponent {
        static final int SAMPLE_MILLIS = 1000;
        static final int HEIGHT = 130;
        private static final int SAMPLES = 60;
        /** The floor of the scale, so a server with nobody on it does not magnify a few bytes into peaks. */
        private static final long SMALLEST_SCALE = 1024;
        private static final Color BACKGROUND = new Color(0x10, 0x14, 0x1c);
        private static final Color GRID = new Color(0x2a, 0x31, 0x3c);
        private static final Color LABEL = new Color(0xc8, 0xd1, 0xdb);
        private static final Color IN = new Color(0xe8, 0xee, 0xf4);
        private static final Color OUT = new Color(0x3d, 0x8b, 0xfd);
        private static final BasicStroke SOLID = new BasicStroke(1.5f);
        private static final BasicStroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10f, new float[] {5f, 3f}, 0f);

        private final long[] in = new long[SAMPLES];
        private final long[] out = new long[SAMPLES];
        /** Where the next sample goes, which is also the oldest one still shown. */
        private int next;
        private TrafficCounter counter;
        private long readBefore;
        private long writtenBefore;

        TrafficGraph() {
            setPreferredSize(new Dimension(400, HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
            setAlignmentX(0f);
        }

        /** The counters only ever grow, so a sample is the difference from the last; a new server starts from zero. */
        void sample(final TrafficCounter now) {
            final long read = now == null ? 0 : now.cumulativeReadBytes();
            final long written = now == null ? 0 : now.cumulativeWrittenBytes();
            if (now != counter) {
                counter = now;
                readBefore = read;
                writtenBefore = written;
            }
            in[next] = read - readBefore;
            out[next] = written - writtenBefore;
            readBefore = read;
            writtenBefore = written;
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

            long top = SMALLEST_SCALE;
            for (int i = 0; i < SAMPLES; i++) {
                top = Math.max(top, Math.max(in[i], out[i]));
            }
            final int plotTop = 22;
            final int plotHeight = h - plotTop - 4;
            g2.setColor(GRID);
            g2.drawLine(0, plotTop, w, plotTop);
            g2.drawLine(0, h - 4, w, h - 4);
            line(g2, in, top, plotTop, plotHeight, IN, SOLID);
            line(g2, out, top, plotTop, plotHeight, OUT, DASHED);

            final int latest = (next + SAMPLES - 1) % SAMPLES;
            g2.setFont(getFont().deriveFont(11f));
            final int baseline = 14;
            int x = 8;
            x = legend(g2, x, baseline, IN, SOLID, "In " + rate(in[latest]));
            legend(g2, x + 16, baseline, OUT, DASHED, "Out " + rate(out[latest]));
            final String scale = "top " + rate(top) + " · last minute";
            g2.setColor(LABEL);
            g2.drawString(scale, w - 8 - g2.getFontMetrics().stringWidth(scale), baseline);
            g2.dispose();
        }

        private void line(final Graphics2D g2, final long[] values, final long top, final int plotTop,
                final int plotHeight, final Color colour, final BasicStroke stroke) {
            final int[] xs = new int[SAMPLES];
            final int[] ys = new int[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                final long v = values[(next + i) % SAMPLES];
                xs[i] = i * (getWidth() - 1) / (SAMPLES - 1);
                ys[i] = plotTop + plotHeight - (int) (v * plotHeight / top);
            }
            g2.setColor(colour);
            g2.setStroke(stroke);
            g2.drawPolyline(xs, ys, SAMPLES);
        }

        /** A short swatch of the line followed by its figure; returns where the next entry can start. */
        private static int legend(final Graphics2D g2, final int x, final int baseline, final Color colour,
                final BasicStroke stroke, final String text) {
            g2.setColor(colour);
            g2.setStroke(stroke);
            g2.drawLine(x, baseline - 4, x + 18, baseline - 4);
            g2.setColor(LABEL);
            g2.drawString(text, x + 24, baseline);
            return x + 24 + g2.getFontMetrics().stringWidth(text);
        }

        private static String rate(final long bytesPerSecond) {
            return bytes(bytesPerSecond) + "/s";
        }
    }

    /** Beside the graph, in its colours: how long the server has been up, who is on it, and every byte so far. */
    private static final class StatsBox extends JComponent {
        private static final int WIDTH = 210;
        private static final String[] NAMES = {"Up", "Players", "Received", "Sent"};
        private String[] values = {"—", "0", "—", "—"};

        StatsBox() {
            final Dimension size = new Dimension(WIDTH, TrafficGraph.HEIGHT);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        /** The counter is made when the server starts, so its first cumulative time is when it started. */
        void show(final TrafficCounter counter, final int players) {
            values = counter == null
                    ? new String[] {"—", "0", "—", "—"}
                    : new String[] {uptime(System.currentTimeMillis() - counter.lastCumulativeTime()),
                            String.valueOf(players), bytes(counter.cumulativeReadBytes()),
                            bytes(counter.cumulativeWrittenBytes())};
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(TrafficGraph.BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(getFont().deriveFont(11f));
            g2.setColor(TrafficGraph.LABEL);
            for (int i = 0; i < NAMES.length; i++) {
                final int baseline = 24 + i * 30;
                g2.drawString(NAMES[i], 10, baseline);
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
        text.append(chunk);
        trim();
        text.setCaretPosition(text.getDocument().getLength());
    }

    private void trim() {
        final int lines = text.getLineCount();
        if (lines <= MAX_LINES) {
            return;
        }
        try {
            text.replaceRange("", 0, text.getLineEndOffset(lines - MAX_LINES - 1));
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
