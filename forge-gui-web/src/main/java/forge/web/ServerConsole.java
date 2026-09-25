package forge.web;

import forge.gamemodes.net.server.FServerManager;
import forge.gui.interfaces.IProgressBar;
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
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
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
import java.util.LinkedHashMap;
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

    private final WebGuiBase ui;
    private final Runnable onQuit;
    private final StringBuilder pending = new StringBuilder();
    private final Light light = new Light();
    private final JLabel state = new JLabel("Starting");
    private final JPanel links = new JPanel();
    private final JProgressBar progress = new JProgressBar();
    private final JCheckBox quitWhenEmpty = new JCheckBox("Quit when the last player leaves", true);
    private final JCheckBox forwardPort = new JCheckBox("Ask the router to forward the port, so players on the internet can join");
    private final JLabel forwardState = new JLabel();
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
        SwingUtilities.invokeLater(() -> forwardState.setText(switch (now) {
            case OFF -> "";
            case ASKING -> "Asking the router…";
            case FORWARDED -> "The router is forwarding port " + service.port() + ".";
            case REFUSED -> "The router did not forward the port. Turn on UPnP in its settings, or forward port "
                    + service.port() + " by hand.";
        }));
        if (now == WebService.Forwarding.FORWARDED || now == WebService.Forwarding.REFUSED) {
            lookUpAddresses();
        }
    }

    /** The port is closed: there is nothing to link to until it is started again. */
    private void stopped() {
        SwingUtilities.invokeLater(() -> {
            light.lit(false);
            state.setText("Stopped");
            startStop.setText("Start server");
            browse.setEnabled(false);
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString("Stopped");
            showLinks(Map.of());
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
        final Map<String, String> found = new LinkedHashMap<>();
        found.put("On this PC", service.url());
        for (final Map.Entry<String, String> local : FServerManager.getAllLocalAddresses().entrySet()) {
            found.put(local.getKey(), service.inviteUrl(local.getValue()));
        }
        final String external = FServerManager.getExternalAddress();
        found.put(WebSessions.internetCaption(service.port(), service.forwarding() == WebService.Forwarding.FORWARDED),
                external == null ? null : service.inviteUrl(external));
        SwingUtilities.invokeLater(() -> showLinks(found));
    }

    /** One row per link, each with a copy button of its own. Rebuilt whenever the server starts or stops. */
    private void showLinks(final Map<String, String> found) {
        links.removeAll();
        if (found.isEmpty()) {
            links.add(row("", new JLabel("—")));
        }
        boolean first = true;
        for (final Map.Entry<String, String> link : found.entrySet()) {
            if (!first) {
                links.add(Box.createVerticalStrut(6));
            }
            first = false;
            links.add(linkRow(link.getKey(), link.getValue()));
        }
        links.revalidate();
        links.repaint();
    }

    private JPanel linkRow(final String caption, final String address) {
        final JLabel value = new JLabel(address == null ? "could not be found" : address);
        value.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final JPanel line = row(caption, value);
        if (address != null) {
            line.add(Box.createHorizontalStrut(8));
            line.add(button("Copy", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(address), null)));
        }
        return line;
    }

    private void build() {
        final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

        links.setLayout(new BoxLayout(links, BoxLayout.PAGE_AXIS));
        links.setAlignmentX(0f);
        showLinks(Map.of());

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
        forwardPort.setAlignmentX(0f);
        forwardPort.setEnabled(false);
        forwardPort.addActionListener(e -> {
            final boolean on = forwardPort.isSelected();
            inBackground("ForgePortForward", () -> service.forwardPort(on));
        });
        forwardState.setAlignmentX(0f);
        forwardState.setBorder(BorderFactory.createEmptyBorder(2, 24, 0, 0));

        final JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.PAGE_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        head.add(status);
        head.add(Box.createVerticalStrut(14));
        head.add(links);
        head.add(Box.createVerticalStrut(14));
        head.add(progress);
        head.add(Box.createVerticalStrut(10));
        head.add(quitWhenEmpty);
        head.add(forwardPort);
        head.add(forwardState);

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
        frame.getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        // Wide enough for the longest link row, caption and copy button included, without a sideways scrollbar
        frame.setSize(new Dimension(980, 620));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(FLUSH_MILLIS, e -> flush()).start();
    }

    private static JPanel row(final String caption, final JLabel value) {
        final JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.LINE_AXIS));
        line.setAlignmentX(0f);
        final JLabel name = new JLabel(caption);
        name.setPreferredSize(new Dimension(130, name.getPreferredSize().height));
        name.setMaximumSize(name.getPreferredSize());
        line.add(name);
        line.add(value);
        line.add(Box.createHorizontalGlue());
        return line;
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
