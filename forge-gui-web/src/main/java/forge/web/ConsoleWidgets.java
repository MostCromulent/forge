package forge.web;

import forge.util.Localizer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Supplier;

/** The rows, boxes, buttons and wrapped text the console's windows are laid out from. */
final class ConsoleWidgets {
    private static final Localizer TEXT = Localizer.getInstance();
    private static final Color LINK = new Color(0x1f, 0x5f, 0xc4);
    static final Color WARN = new Color(0x9a, 0x5b, 0x00);
    private static final Color WARN_FILL = new Color(0xfd, 0xf1, 0xd8);
    static final Color WARN_TEXT = new Color(0x1a, 0x20, 0x2b);
    static final Color NOTE = new Color(0x1f, 0x5f, 0xc4);
    private static final Color NOTE_FILL = new Color(0xe9, 0xf0, 0xfc);
    private static final Color FIELD_EDGE = new Color(0xc9, 0xcd, 0xd4);

    private ConsoleWidgets() {
    }

    static JButton button(final String label, final Runnable action) {
        final JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A button drawn as coloured text, for what leads somewhere rather than does something. */
    static JButton link(final String label, final Runnable action) {
        return asLink(button(label, action));
    }

    static JButton asLink(final JButton b) {
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setForeground(LINK);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** A button that copies what it is given, and says so for a moment. */
    static JButton copyButton(final String label, final Supplier<String> what) {
        final JButton b = new JButton(label);
        final Timer reset = new Timer(2000, e -> b.setText(label));
        reset.setRepeats(false);
        b.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(what.get()), null);
            b.setText(TEXT.getMessage("lblWebMatchBarCopied"));
            reset.restart();
        });
        return b;
    }

    static JLabel heading(final String words) {
        final JLabel label = new JLabel(words);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    static JLabel heading(final String words, final Color colour) {
        final JLabel label = heading(words);
        label.setForeground(colour);
        return label;
    }

    /** A white box around an address or a state and the button that acts on it, so the two read as one thing. */
    static JPanel field(final Component... parts) {
        final JPanel field = row(parts);
        field.setOpaque(true);
        field.setBackground(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(FIELD_EDGE),
                BorderFactory.createEmptyBorder(5, 10, 5, 6)));
        return field;
    }

    static JLabel muted(final String words) {
        final JLabel label = new JLabel(words);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    /** Text that wraps at a set width, which a label cannot do. */
    static JTextArea wrapped(final String words, final Color colour, final int width) {
        final JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(colour);
        reword(area, words, width);
        return area;
    }

    /** A wrapped text's height follows from its width, so both are fixed again whenever its words change. */
    static void reword(final JTextArea area, final String words, final int width) {
        area.setText(words);
        // Room to the right: on a scaled display a line that just fits when measured is drawn a little wider, and would lose its last letters
        area.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, width / 20));
        area.setPreferredSize(null);
        area.setSize(width, Short.MAX_VALUE);
        final Dimension size = new Dimension(width, area.getPreferredSize().height);
        area.setPreferredSize(size);
        area.setMaximumSize(size);
    }

    static JPanel row(final Component... parts) {
        final JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.LINE_AXIS));
        row.setOpaque(false);
        for (final Component part : parts) {
            row.add(part);
        }
        return row;
    }

    static JPanel stack(final JComponent upper, final JComponent lower) {
        final JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.PAGE_AXIS));
        stack.setOpaque(false);
        upper.setAlignmentX(0f);
        lower.setAlignmentX(0f);
        stack.add(upper);
        stack.add(Box.createVerticalStrut(2));
        stack.add(lower);
        return stack;
    }

    /** Adds one group of rows to the window, with a line under it. */
    static void section(final JPanel main, final JComponent... parts) {
        for (final JComponent part : parts) {
            part.setAlignmentX(0f);
            main.add(part);
            // The space under a part comes and goes with it, so a hidden one leaves no hole
            final Component gap = Box.createVerticalStrut(9);
            gap.setVisible(part.isVisible());
            part.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentShown(final ComponentEvent e) {
                    gap.setVisible(true);
                }

                @Override
                public void componentHidden(final ComponentEvent e) {
                    gap.setVisible(false);
                }
            });
            main.add(gap);
        }
        final JSeparator line = new JSeparator();
        line.setAlignmentX(0f);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        main.add(Box.createVerticalStrut(2));
        main.add(line);
        main.add(Box.createVerticalStrut(11));
    }

    /** An amber box for what went wrong and what to do about it. */
    static JPanel warning(final JComponent... parts) {
        return box(WARN, WARN_FILL, parts);
    }

    /** A blue box for something the host is asked or has to do, which is not a fault. */
    static JPanel note(final JComponent... parts) {
        return box(NOTE, NOTE_FILL, parts);
    }

    static JPanel box(final Color edge, final Color fill, final JComponent... parts) {
        final JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.PAGE_AXIS));
        box.setBackground(fill);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, edge),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        for (final JComponent part : parts) {
            part.setAlignmentX(0f);
            box.add(part);
            box.add(Box.createVerticalStrut(7));
        }
        return box;
    }
}
