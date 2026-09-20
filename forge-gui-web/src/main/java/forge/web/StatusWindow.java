package forge.web;

import org.tinylog.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A small window so the game is visibly running and can be stopped. The browser is the whole interface, so
 * without this a player who closes their tab has no sign the process is still there. Closing this window
 * stops it. A machine with no display, or one told not to, gets nothing and behaves as before.
 */
final class StatusWindow {
    /** Enough to see what just happened without holding a session's whole output. */
    private static final int MAX_LINES = 300;
    /** Card loading prints thousands of lines, so the text area is fed on a timer rather than per line. */
    private static final int FLUSH_MILLIS = 250;

    private final String url;
    private final WebGuiBase ui;
    private final Runnable onQuit;
    private final StringBuilder pending = new StringBuilder();
    private JFrame frame;
    private JTextArea text;
    private volatile boolean quitting;

    private StatusWindow(final String url, final WebGuiBase ui, final Runnable onQuit) {
        this.url = url;
        this.ui = ui;
        this.onQuit = onQuit;
    }

    /** Opens the window, or returns null when there is no display or the flag turns it off. */
    static StatusWindow open(final String url, final WebGuiBase ui, final Runnable onQuit) {
        if (Boolean.getBoolean("forge.web.noWindow") || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final StatusWindow window = new StatusWindow(url, ui, onQuit);
        try {
            SwingUtilities.invokeAndWait(window::build);
        } catch (final Exception e) {
            Logger.warn(e, "Could not open the status window");
            return null;
        }
        window.captureOutput();
        return window;
    }

    private void build() {
        text = new JTextArea();
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(new Color(0x10, 0x14, 0x1c));
        text.setForeground(new Color(0xc8, 0xd1, 0xdb));
        text.setMargin(new java.awt.Insets(6, 8, 6, 8));

        final JLabel where = new JLabel(url);
        where.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.LINE_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        head.add(where);
        head.add(Box.createHorizontalGlue());
        head.add(button("Copy link", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(url), null)));
        head.add(Box.createHorizontalStrut(6));
        head.add(button("Open browser", this::openBrowser));
        head.add(Box.createHorizontalStrut(6));
        head.add(button("Quit Forge", this::quit));

        frame = new JFrame("Forge (web)");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                quit();
            }
        });
        frame.getContentPane().add(head, BorderLayout.NORTH);
        frame.getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        frame.setSize(new Dimension(760, 420));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(FLUSH_MILLIS, e -> flush()).start();
    }

    private JButton button(final String label, final Runnable action) {
        final JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void openBrowser() {
        WebMain.openBrowser(url, ui);
    }

    private void quit() {
        if (quitting) {
            return;
        }
        quitting = true;
        onQuit.run();
    }

    /** Everything the process prints also lands in the window, which is the log a player can actually see. */
    private void captureOutput() {
        System.setOut(tee(System.out));
        System.setErr(tee(System.err));
    }

    private PrintStream tee(final PrintStream original) {
        // var keeps the anonymous type, so drain() is callable without ByteArrayOutputStream's throwing flush
        final var copy = new ByteArrayOutputStream() {
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
                copy.write(b);
                if (b == '\n') {
                    copy.drain();
                }
            }

            // Card loading prints tens of thousands of lines, and a byte at a time is too slow for that
            @Override
            public void write(final byte[] bytes, final int off, final int len) {
                original.write(bytes, off, len);
                copy.write(bytes, off, len);
                copy.drain();
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
        } catch (final javax.swing.text.BadLocationException e) {
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
