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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The desktop window that starts and stops the server, without which a player who closes their tab has no sign Forge still runs. */
final class ServerConsole implements IProgressBar {
    private static final Localizer TEXT = Localizer.getInstance();
    /** Enough to see what just happened without holding a whole session's output. */
    private static final int MAX_LINES = 300;
    /** Card loading prints tens of thousands of lines, so the text is fed on a timer rather than per line. */
    private static final int FLUSH_MILLIS = 250;
    /** How wide a line of text runs before it wraps, which sets the width of the window. */
    private static final int TEXT_WIDTH = 480;
    private static final int HELP_WIDTH = TEXT_WIDTH - 26;
    private static final int DETAILS_WIDTH = 440;
    private static final String GUIDE = "https://github.com/Card-Forge/forge/wiki/Network-Play#network-configuration";
    /** Adapters the rest of the home network cannot reach, though one may be this machine's route to the internet and so be listed first. */
    private static final Pattern NOT_HOME = Pattern.compile("vpn|virtual|hamachi|zerotier|tailscale|wireguard|hyper-v|vmware|wsl|bluetooth",
            Pattern.CASE_INSENSITIVE);

    /** Running is a filled lamp, stopped an empty ring: the shape carries it, so the colour need not. */
    private static final Color LIT = new Color(0x3f, 0xb9, 0x50);
    private static final Color DARK = new Color(0xd0, 0x57, 0x4e);
    private static final Color LINK = new Color(0x1f, 0x5f, 0xc4);
    private static final Color WARN = new Color(0x9a, 0x5b, 0x00);
    private static final Color WARN_FILL = new Color(0xfd, 0xf1, 0xd8);
    private static final Color WARN_TEXT = new Color(0x1a, 0x20, 0x2b);

    private final WebGuiBase ui;
    private final Runnable onQuit;
    private final StringBuilder pending = new StringBuilder();
    private WebService service;
    /** Whether the server is up and the console is not in the middle of starting or stopping it. */
    private boolean up;
    private Map<String, String> homes = Map.of();
    /** The adapter and address the network link is made from, or null when this machine has none. */
    private Map.Entry<String, String> home;
    private String internet;
    private int unseenErrors;
    private final Light light = new Light();
    private final JLabel state = new JLabel(TEXT.getMessage("lblWebConsoleStartingForge"));
    private final JLabel keepOpen = muted(TEXT.getMessage("lblWebConsoleKeepOpen"));
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea startFailure = wrapped("", WARN_TEXT, HELP_WIDTH);
    private final JLabel homeAddress = muted("—");
    private final JLabel netAddress = muted("—");
    private final JButton copyHome = copyButton(TEXT.getMessage("lblWebConsoleCopyLink"), () -> service.inviteUrl(home.getValue()));
    private final JButton moreHome = new BasicArrowButton(SwingConstants.SOUTH);
    private final JButton copyNet = copyButton(TEXT.getMessage("lblWebConsoleCopyLink"), () -> service.inviteUrl(internet));
    private final JCheckBox quitWhenEmpty = new JCheckBox(TEXT.getMessage("lblWebConsoleQuitWhenEmpty"), true);
    private final JCheckBox forwardPort = new JCheckBox(TEXT.getMessage("lblWebConsoleForwardPort"));
    private final Light forwardLight = new Light();
    private final JTextArea forwardState = wrapped("", UIManager.getColor("Label.foreground"), TEXT_WIDTH - 40);
    private final JLabel refused = new JLabel();
    private final JLabel players = muted(" ");
    private final JPanel details = new JPanel(new BorderLayout(0, 8));
    private final TrafficGraph graph = new TrafficGraph();
    private final StatsBox stats = new StatsBox();
    private final JButton imagesButton = new JButton(TEXT.getMessage("lblWebImagesButton"));
    private final CardImageDownloads images = new CardImageDownloads(label -> SwingUtilities.invokeLater(() -> imagesButton.setText(label)));
    /** One thread, so two quick clicks on the port option reach the router in the order they were made. */
    private final ExecutorService router = Executors.newSingleThreadExecutor(work -> {
        final Thread t = new Thread(work, "ForgePortForward");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerTraffic traffic;
    private JFrame frame;
    private JTextPane text;
    /** The colour of the last error or warning written, so the stack trace under it is coloured with it. */
    private SimpleAttributeSet carried = PLAIN;
    private JButton browse;
    private JButton startStop;
    private JButton retryNet;
    private JButton detailsToggle;
    private JPanel startHelp;
    private JPanel forwardStateRow;
    private JPanel forwardHelp;
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
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (final ReflectiveOperationException | UnsupportedLookAndFeelException e) {
            Logger.warn(e, "Could not use the system's look for the console");
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

    /** Names the step of the start-up that is running. */
    void starting(final String what) {
        SwingUtilities.invokeLater(() -> busy(what));
    }

    /** The server has been started, and this is what the buttons drive from here on. failure is why it could not be, or null. */
    void attach(final WebService driven, final Exception failure) {
        service = driven;
        images.ready();
        SwingUtilities.invokeLater(() -> {
            quitWhenEmpty.setEnabled(true);
            forwardPort.setEnabled(true);
            imagesButton.setEnabled(true);
        });
        driven.onForwarding(this::forwarding);
        if (driven.running()) {
            running();
        } else {
            stopped(failure);
        }
    }

    private void busy(final String what) {
        up = false;
        state.setText(what);
        keepOpen.setVisible(false);
        startStop.setVisible(false);
        startHelp.setVisible(false);
        progress.setIndeterminate(true);
        progress.setString("");
        progress.setVisible(true);
        browse.setEnabled(false);
        copyHome.setEnabled(false);
        copyNet.setEnabled(false);
    }

    /** The port is bound: the lamp is lit and the links are worth copying. */
    private void running() {
        traffic = service.traffic();
        SwingUtilities.invokeLater(() -> {
            up = true;
            light.lit(true);
            state.setText(TEXT.getMessage("lblWebConsoleRunning"));
            keepOpen.setVisible(true);
            startStop.setText(TEXT.getMessage("lblWebConsoleStopServer"));
            startStop.setVisible(true);
            progress.setVisible(false);
            browse.setEnabled(true);
            frame.getRootPane().setDefaultButton(null);
            showInternet(null, TEXT.getMessage("lblWebMatchBarWorkingOutAddress"), false);
            refit();
        });
        inBackground("ForgeAddresses", () -> {
            final Map<String, String> local = FServerManager.getAllLocalAddresses();
            local.values().removeIf("localhost"::equals);
            SwingUtilities.invokeLater(() -> {
                if (up) {
                    showHome(local);
                }
            });
            findInternet();
        });
    }

    /** For work that waits on the network or the router, which the event thread must not do. */
    private static void inBackground(final String name, final Runnable work) {
        final Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }

    /** Shows where asking the router stands, in the words a router's own settings use. */
    private void forwarding(final WebService.Forwarding now) {
        SwingUtilities.invokeLater(() -> {
            final boolean wanted = service.forwardPort();
            forwardPort.setSelected(wanted);
            forwardLight.lit(now == WebService.Forwarding.FORWARDED);
            // A refusal only says UPnP did not do it; the port may still be forwarded by hand, so the link stays usable
            refused.setText(TEXT.getMessage("lblWebConsoleRefused", service.port()));
            forwardHelp.setVisible(now == WebService.Forwarding.REFUSED);
            final String words = switch (now) {
                case OFF -> wanted ? "" : TEXT.getMessage("lblWebConsoleUpnpOff", service.port());
                case ASKING -> TEXT.getMessage("lblWebConsoleAskingRouter");
                case FORWARDED -> TEXT.getMessage("lblWebConsoleForwarded", service.port());
                case REFUSED -> "";
            };
            reword(forwardState, words, TEXT_WIDTH - 40);
            forwardStateRow.setVisible(!words.isEmpty());
            refit();
        });
    }

    /** The port is closed: there is nothing to link to until it is started again. failure is why a start did not work, or null. */
    private void stopped(final Exception failure) {
        traffic = null;
        SwingUtilities.invokeLater(() -> {
            up = false;
            light.lit(false);
            state.setText(TEXT.getMessage(failure == null ? "lblWebConsoleStopped" : "lblWebConsoleCouldNotStart"));
            keepOpen.setVisible(false);
            startStop.setText(TEXT.getMessage("lblWebConsoleStartServer"));
            startStop.setVisible(true);
            progress.setVisible(false);
            browse.setEnabled(false);
            showHome(Map.of());
            showInternet(null, "—", false);
            startHelp.setVisible(failure != null);
            if (failure != null) {
                // All a failed bind says is that the port is taken; another Forge is the likely holder, since desktop Forge hosts on the same port
                reword(startFailure, failure instanceof BindException ? TEXT.getMessage("lblWebConsolePortInUse", service.port())
                        : TEXT.getMessage("lblWebConsoleStartFailed"), HELP_WIDTH);
                details.setVisible(true);
                unseenErrors = 0;
                labelDetails();
            }
            frame.getRootPane().setDefaultButton(startStop);
            refit();
        });
    }

    /** Start or stop, off the event thread because binding a port and closing one both take their time. */
    private void toggle() {
        final boolean stopping = up;
        if (stopping && service.playersHere() > 0 && !confirmed("lblWebConsoleStopAsk", "lblWebConsoleStopServer")) {
            return;
        }
        busy(TEXT.getMessage(stopping ? "lblWebConsoleStoppingServer" : "lblWebConsoleStartingServer"));
        inBackground("ForgeServerControl", () -> {
            Exception failure = null;
            try {
                if (stopping) {
                    service.stop();
                } else {
                    service.start();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                // Netty rethrows a failed bind's checked exception without declaring it
                Logger.error(e, "Could not {} the server", stopping ? "stop" : "start");
                failure = stopping ? null : e;
            }
            if (service.running()) {
                running();
            } else {
                stopped(failure);
            }
        });
    }

    /** Asks before something that drops players who are connected. */
    private boolean confirmed(final String question, final String yes) {
        final String[] answers = {TEXT.getMessage(yes), TEXT.getMessage("lblCancel")};
        return JOptionPane.showOptionDialog(frame, TEXT.getMessage(question), frame.getTitle(), JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, answers, answers[1]) == 0;
    }

    private void showHome(final Map<String, String> found) {
        homes = found;
        moreHome.setVisible(found.size() > 1);
        home = found.entrySet().stream().filter(a -> !NOT_HOME.matcher(a.getKey()).find()).findFirst()
                .orElse(found.entrySet().stream().findFirst().orElse(null));
        showHomeChoice();
    }

    private void showHomeChoice() {
        homeAddress.setText(home == null ? "—" : home.getKey() + "  " + home.getValue());
        copyHome.setEnabled(home != null);
        copyHome.setToolTipText(home == null ? null : service.inviteUrl(home.getValue()));
    }

    /** address is null while it is being found, when it could not be, and while stopped; shown is what to say in its place. */
    private void showInternet(final String address, final String shown, final boolean mayRetry) {
        internet = address;
        netAddress.setText(address == null ? shown : address);
        copyNet.setEnabled(address != null);
        copyNet.setToolTipText(address == null ? null : service.inviteUrl(address));
        retryNet.setVisible(mayRetry);
    }

    /** Asked off the event thread, because the address the internet sees is a web request of its own. */
    private void findInternet() {
        final String found = FServerManager.getExternalAddress();
        SwingUtilities.invokeLater(() -> {
            if (up) {
                showInternet(found, TEXT.getMessage("lblWebConsoleNoAddress"), found == null);
            }
        });
    }

    private void build() {
        state.setFont(state.getFont().deriveFont(Font.BOLD, state.getFont().getSize2D() + 2f));
        keepOpen.setVisible(false);
        progress.setStringPainted(true);
        progress.setString("");
        progress.setIndeterminate(true);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        startStop = button(TEXT.getMessage("lblWebConsoleStopServer"), this::toggle);
        startStop.setVisible(false);
        startHelp = warning(startFailure, row(copyButton(TEXT.getMessage("lblWebConsoleCopyLog"), () -> text.getText())));
        startHelp.setVisible(false);

        browse = button(TEXT.getMessage("lblWebConsoleOpenBrowser"), () -> WebMain.openBrowser(service.url(), ui));
        browse.setEnabled(false);

        copyHome.setEnabled(false);
        copyNet.setEnabled(false);
        moreHome.setVisible(false);
        moreHome.setToolTipText(TEXT.getMessage("lblWebConsoleOtherAddresses"));
        moreHome.getAccessibleContext().setAccessibleName(TEXT.getMessage("lblWebConsoleOtherAddresses"));
        moreHome.addActionListener(e -> {
            final JPopupMenu menu = new JPopupMenu();
            for (final Map.Entry<String, String> address : homes.entrySet()) {
                menu.add(address.getKey() + "  " + address.getValue()).addActionListener(picked -> {
                    home = address;
                    showHomeChoice();
                });
            }
            menu.show(moreHome, 0, moreHome.getHeight());
        });
        retryNet = button(TEXT.getMessage("lblTryAgain"), () -> {
            showInternet(null, TEXT.getMessage("lblWebMatchBarWorkingOutAddress"), false);
            inBackground("ForgeAddresses", this::findInternet);
        });
        retryNet.setVisible(false);

        forwardPort.setEnabled(false);
        forwardPort.addActionListener(e -> {
            final boolean on = forwardPort.isSelected();
            if (!on && service.playersHere() > 1 && !confirmed("lblWebConsoleUnforwardAsk", "lblWebConsoleStopForwarding")) {
                forwardPort.setSelected(true);
                return;
            }
            router.execute(() -> service.forwardPort(on));
        });
        forwardStateRow = row(Box.createHorizontalStrut(22), forwardLight, Box.createHorizontalStrut(6), forwardState);
        forwardStateRow.setVisible(false);
        refused.setForeground(WARN);
        refused.setFont(refused.getFont().deriveFont(Font.BOLD));
        forwardHelp = warning(refused, wrapped(TEXT.getMessage("lblWebConsoleRefusedAbout"), WARN_TEXT, HELP_WIDTH),
                row(button(TEXT.getMessage("lblWebConsoleSetupGuide"), this::openGuide), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblTryAgain"), () -> router.execute(() -> service.forwardPort(true))), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblWebConsoleForwardedMyself"), () -> router.execute(() -> service.forwardPort(false)))));
        forwardHelp.setVisible(false);

        quitWhenEmpty.setEnabled(false);
        quitWhenEmpty.addActionListener(e -> service.quitWhenEmpty(quitWhenEmpty.isSelected()));
        imagesButton.addActionListener(e -> images.show(frame));
        imagesButton.setEnabled(false);
        detailsToggle = link("", () -> showDetails(!details.isVisible()));
        labelDetails();

        final JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.PAGE_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
        section(main, step(1), row(light, Box.createHorizontalStrut(9), stack(state, keepOpen), Box.createHorizontalGlue(), startStop), progress, startHelp);
        section(main, step(2), row(stack(heading(TEXT.getMessage("lblPlay")), wrapped(TEXT.getMessage("lblWebConsolePlayAbout"), UIManager.getColor("Label.disabledForeground"), TEXT_WIDTH - 150)),
                Box.createHorizontalGlue(), browse));
        section(main, step(3), stack(heading(TEXT.getMessage("lblWebConsoleInvitePlayers")), wrapped(TEXT.getMessage("lblWebConsoleInviteAbout"), UIManager.getColor("Label.disabledForeground"), TEXT_WIDTH)),
                row(stack(new JLabel(TEXT.getMessage("lblWebConsoleOnNetwork")), homeAddress), Box.createHorizontalGlue(), copyHome, moreHome),
                row(stack(new JLabel(TEXT.getMessage("lblWebConsoleOnInternet")), netAddress), Box.createHorizontalGlue(), retryNet,
                        Box.createHorizontalStrut(8), copyNet),
                forwardPort, forwardStateRow, forwardHelp,
                row(players, Box.createHorizontalGlue(), link(TEXT.getMessage("lblWebConsoleGuide"), this::openGuide)));
        section(main, muted(TEXT.getMessage("lblWebHeadOptions")),
                row(stack(new JLabel(TEXT.getMessage("lblWebImagesTitle")), muted(TEXT.getMessage("lblWebConsoleImagesAbout"))),
                        Box.createHorizontalGlue(), imagesButton),
                quitWhenEmpty);
        final JPanel foot = row(Box.createHorizontalGlue(), detailsToggle);
        // A row left centred would push every left-aligned one in by half its width
        foot.setAlignmentX(0f);
        main.add(foot);

        // A styled pane so errors and warnings can stand out, and it wraps long lines because a stack trace is wider than the window
        text = new JTextPane();
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(new Color(0x10, 0x14, 0x1c));
        text.setForeground(new Color(0xc8, 0xd1, 0xdb));
        text.setMargin(new Insets(6, 8, 6, 8));

        final JPanel numbers = new JPanel();
        numbers.setLayout(new BoxLayout(numbers, BoxLayout.PAGE_AXIS));
        final JPanel title = row(heading(TEXT.getMessage("lblDetails")), Box.createHorizontalGlue(),
                copyButton(TEXT.getMessage("lblWebConsoleCopyLog"), () -> text.getText()));
        title.setAlignmentX(0f);
        numbers.add(title);
        numbers.add(Box.createVerticalStrut(10));
        numbers.add(graph);
        numbers.add(stats);
        final JScrollPane log = new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        log.setBorder(null);
        log.getViewport().setBackground(text.getBackground());
        details.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0, 0, 0, 40)),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        details.add(numbers, BorderLayout.NORTH);
        details.add(log, BorderLayout.CENTER);
        details.setPreferredSize(new Dimension(DETAILS_WIDTH, 480));
        details.setVisible(false);

        frame = new JFrame(TEXT.getMessage("lblWebConsoleTitle"));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                if (up && service.playersHere() > 0 && !confirmed("lblWebConsoleCloseAsk", "lblWebConsoleCloseForge")) {
                    return;
                }
                quit();
            }
        });
        // Held at the top of a scrolling pane, so the rows keep their own heights and a screen too short for them can still reach them all
        final JPanel top = new JPanel(new BorderLayout());
        top.add(main, BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        frame.getContentPane().add(scroll, BorderLayout.CENTER);
        frame.getContentPane().add(details, BorderLayout.EAST);
        frame.setLocationByPlatform(true);
        refit();
        frame.setVisible(true);

        new Timer(FLUSH_MILLIS, e -> flush()).start();
        new Timer(TrafficGraph.SAMPLE_MILLIS, e -> {
            final ServerTraffic now = traffic;
            final int here = service == null ? 0 : service.playersHere();
            graph.sample(now);
            stats.show(now, here);
            players.setText(up ? TEXT.getMessage("lblWebConsolePlayersConnected", here) : " ");
        }).start();
    }

    /** The window is as tall as what it shows, which changes as help and the details come and go. */
    private void refit() {
        frame.pack();
        frame.setSize(frame.getWidth(), Math.min(frame.getHeight(), GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().height));
    }

    private void showDetails(final boolean shown) {
        details.setVisible(shown);
        unseenErrors = 0;
        labelDetails();
        refit();
    }

    /** While the log is out of sight, the way to it says how many errors have been written there. */
    private void labelDetails() {
        if (details.isVisible()) {
            detailsToggle.setText("« " + TEXT.getMessage("lblWebConsoleHideDetails"));
        } else {
            detailsToggle.setText((unseenErrors == 0 ? TEXT.getMessage("lblWebConsoleShowDetails")
                    : TEXT.getMessage("lblWebConsoleShowDetailsErrors", unseenErrors)) + " »");
        }
    }

    private void openGuide() {
        try {
            ui.browseToUrl(GUIDE);
        } catch (final IOException | URISyntaxException e) {
            Logger.warn(e, "Open {} in a browser", GUIDE);
        }
    }

    private static JButton button(final String label, final Runnable action) {
        final JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A button drawn as coloured text, for what leads somewhere rather than does something. */
    private static JButton link(final String label, final Runnable action) {
        final JButton b = button(label, action);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setForeground(LINK);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** A button that copies what it is given, and says so for a moment. */
    private static JButton copyButton(final String label, final Supplier<String> what) {
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

    private static JLabel heading(final String words) {
        final JLabel label = new JLabel(words);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel step(final int number) {
        final JLabel label = heading(TEXT.getMessage("lblWebConsoleStep", number));
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() + 3f));
        label.setForeground(LINK);
        return label;
    }

    private static JLabel muted(final String words) {
        final JLabel label = new JLabel(words);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    /** Text that wraps at a set width, which a label cannot do. */
    private static JTextArea wrapped(final String words, final Color colour, final int width) {
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
    private static void reword(final JTextArea area, final String words, final int width) {
        area.setText(words);
        area.setPreferredSize(null);
        area.setSize(width, Short.MAX_VALUE);
        final Dimension size = new Dimension(width, area.getPreferredSize().height);
        area.setPreferredSize(size);
        area.setMaximumSize(size);
    }

    private static JPanel row(final Component... parts) {
        final JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.LINE_AXIS));
        row.setOpaque(false);
        for (final Component part : parts) {
            row.add(part);
        }
        return row;
    }

    private static JPanel stack(final JComponent upper, final JComponent lower) {
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

    /** Adds one captioned group of rows to the window, with a line under it. */
    private static void section(final JPanel main, final JLabel caption, final JComponent... parts) {
        caption.setAlignmentX(0f);
        main.add(caption);
        main.add(Box.createVerticalStrut(7));
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
        main.add(Box.createVerticalStrut(4));
        main.add(line);
        main.add(Box.createVerticalStrut(13));
    }

    /** An amber box for what went wrong and what to do about it. */
    private static JPanel warning(final JComponent... parts) {
        final JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.PAGE_AXIS));
        box.setBackground(WARN_FILL);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, WARN),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        for (final JComponent part : parts) {
            part.setAlignmentX(0f);
            box.add(part);
            box.add(Box.createVerticalStrut(7));
        }
        return box;
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
        static final String[] KIND_NAMES = {TEXT.getMessage("lblGame"),
                TEXT.getMessage("lblWebSleevesCardArt"), TEXT.getMessage("lblAudio"),
                TEXT.getMessage("lblWebConsoleKindPage")};
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
            g2.drawString(TEXT.getMessage("lblWebConsoleInOut", rate(received[SAMPLES - 1]), rate(stacked[KINDS.length - 1][SAMPLES - 1])), 8, 14);
            final String scale = TEXT.getMessage("lblWebConsoleScale", rate(top));
            g2.drawString(scale, w - 8 - g2.getFontMetrics().stringWidth(scale), 14);
            int x = 8;
            for (int k = 0; k < KINDS.length; k++) {
                x = swatch(g2, x, 30, KIND_COLOURS[k], KIND_NAMES[k]) + 14;
            }
            g2.setColor(IN);
            g2.setStroke(DASHED);
            g2.drawLine(x, 26, x + 16, 26);
            g2.setColor(LABEL);
            g2.drawString(TEXT.getMessage("lblWebConsoleReceived"), x + 22, 30);
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

    /** Under the graph, in its colours: how long the server has been up, who is on it, and every byte so far. */
    private static final class StatsBox extends JComponent {
        private static final String[] NAMES = {TEXT.getMessage("lblWebConsoleUp"),
                TEXT.getMessage("lblPlayers"), TEXT.getMessage("lblWebConsoleReceived")};
        /** Up, players and received, then what was sent of each kind, then everything sent. */
        private String[] values = new String[NAMES.length + TrafficGraph.KIND_NAMES.length + 1];

        StatsBox() {
            setPreferredSize(new Dimension(210, TrafficGraph.HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, TrafficGraph.HEIGHT));
            setAlignmentX(0f);
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
                    g2.drawString(TEXT.getMessage("lblWebConsoleSentTotal"), 24, baseline);
                } else {
                    final int k = i - NAMES.length;
                    TrafficGraph.swatch(g2, 10, baseline, TrafficGraph.KIND_COLOURS[k], TEXT.getMessage("lblWebConsoleSentKind", TrafficGraph.KIND_NAMES[k]));
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
                if (!details.isVisible() && line.contains("[ERROR]")) {
                    unseenErrors++;
                }
            }
        } catch (final BadLocationException e) {
            text.setText(chunk);
        }
        trim();
        text.setCaretPosition(doc.getLength());
        labelDetails();
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
