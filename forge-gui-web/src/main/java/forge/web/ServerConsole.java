package forge.web;

import forge.gamemodes.net.server.FServerManager;
import forge.gui.interfaces.IProgressBar;
import org.tinylog.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The desktop window that starts the server, shows what it is doing and stops it. The browser is the whole
 * of the game, so without this a player who closes their tab has no sign the process is still there.
 *
 * <p>Three things share this package and are easy to confuse. The <em>server</em> is this process. The
 * <em>console</em> is this window. The <em>browser</em> is the page a player actually plays in.
 *
 * <p>Closing the console stops the server. A machine with no display, or one started with
 * {@code -Dforge.web.noConsole}, gets none of this and behaves as it did before.
 */
final class ServerConsole implements IProgressBar {
    /** Enough to see what just happened without holding a whole session's output. */
    private static final int MAX_LINES = 300;
    /** Card loading prints tens of thousands of lines, so the text is fed on a timer rather than per line. */
    private static final int FLUSH_MILLIS = 250;

    private final Runnable onQuit;
    private final StringBuilder pending = new StringBuilder();
    private final JLabel onThisPc = new JLabel("starting…");
    private final JLabel onNetwork = new JLabel("starting…");
    private final JLabel overInternet = new JLabel("starting…");
    private final JProgressBar progress = new JProgressBar();
    private JFrame frame;
    private JTextArea text;
    private JButton copy;
    private JButton browse;
    private String url;
    private WebGuiBase ui;
    private volatile boolean quitting;

    private ServerConsole(final Runnable onQuit) {
        this.onQuit = onQuit;
    }

    /** Opens the console, or returns null when there is no display or the flag turns it off. */
    static ServerConsole open(final Runnable onQuit) {
        if (Boolean.getBoolean("forge.web.noConsole") || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final ServerConsole console = new ServerConsole(onQuit);
        try {
            SwingUtilities.invokeAndWait(console::build);
        } catch (final Exception e) {
            Logger.warn(e, "Could not open the console");
            return null;
        }
        console.captureOutput();
        return console;
    }

    /** The server is up: the links become real and the buttons start working. */
    void serving(final String pageUrl, final int port, final String token, final WebGuiBase gui) {
        url = pageUrl;
        ui = gui;
        SwingUtilities.invokeLater(() -> {
            onThisPc.setText(pageUrl);
            copy.setEnabled(true);
            browse.setEnabled(true);
            progress.setIndeterminate(false);
            progress.setValue(progress.getMaximum());
            progress.setString("Ready");
        });
        final Thread addresses = new Thread(() -> findAddresses(port, token), "ForgeAddresses");
        addresses.setDaemon(true);
        addresses.start();
    }

    /** Found off the event thread, because the address the internet sees is a web request of its own. */
    private void findAddresses(final int port, final String token) {
        final StringBuilder local = new StringBuilder();
        for (final String address : FServerManager.getAllLocalAddresses().values()) {
            local.append(local.isEmpty() ? "" : "    ").append(link(address, port, token));
        }
        setLine(onNetwork, local.isEmpty() ? "no network address" : local.toString());
        final String external = FServerManager.getExternalAddress();
        setLine(overInternet, external == null ? "could not be found" : link(external, port, token));
    }

    private static String link(final String address, final int port, final String token) {
        return "http://" + address + ":" + port + "/?token=" + token;
    }

    private static void setLine(final JLabel label, final String value) {
        SwingUtilities.invokeLater(() -> label.setText(value));
    }

    private void build() {
        final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        onThisPc.setFont(mono);
        onNetwork.setFont(mono);
        overInternet.setFont(mono);

        final JPanel links = new JPanel();
        links.setLayout(new BoxLayout(links, BoxLayout.PAGE_AXIS));
        links.setAlignmentX(0f);
        links.add(row("On this PC", onThisPc));
        links.add(Box.createVerticalStrut(6));
        links.add(row("On your network", onNetwork));
        links.add(Box.createVerticalStrut(6));
        links.add(row("Over the internet", overInternet));

        progress.setStringPainted(true);
        progress.setString("Starting Forge");
        progress.setIndeterminate(true);
        progress.setAlignmentX(0f);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        copy = button("Copy link", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(url), null));
        browse = button("Open browser", () -> WebMain.openBrowser(url, ui));
        copy.setEnabled(false);
        browse.setEnabled(false);
        final JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.setAlignmentX(0f);
        buttons.add(copy);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(browse);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(button("Quit Forge", this::quit));

        final JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.PAGE_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        head.add(links);
        head.add(Box.createVerticalStrut(14));
        head.add(progress);
        head.add(Box.createVerticalStrut(14));
        head.add(buttons);

        text = new JTextArea();
        text.setEditable(false);
        text.setFont(mono);
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
        frame.setSize(new Dimension(900, 560));
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
