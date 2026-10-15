package forge.web.console;

import forge.gamemodes.net.server.FServerManager;
import forge.gui.download.CdnUuidCache;
import forge.gui.interfaces.IProgressBar;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.util.Localizer;
import forge.util.LogExporter;
import org.tinylog.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static forge.web.console.ConsoleWidgets.NOTE;
import static forge.web.console.ConsoleWidgets.WARN;
import static forge.web.console.ConsoleWidgets.WARN_TEXT;
import static forge.web.console.ConsoleWidgets.asLink;
import static forge.web.console.ConsoleWidgets.button;
import static forge.web.console.ConsoleWidgets.copyButton;
import static forge.web.console.ConsoleWidgets.field;
import static forge.web.console.ConsoleWidgets.heading;
import static forge.web.console.ConsoleWidgets.link;
import static forge.web.console.ConsoleWidgets.muted;
import static forge.web.console.ConsoleWidgets.note;
import static forge.web.console.ConsoleWidgets.reword;
import static forge.web.console.ConsoleWidgets.row;
import static forge.web.console.ConsoleWidgets.section;
import static forge.web.console.ConsoleWidgets.stack;
import static forge.web.console.ConsoleWidgets.warning;
import static forge.web.console.ConsoleWidgets.wrapped;
import forge.web.WebGuiBase;
import forge.web.WebMain;
import forge.web.WebService;
import forge.web.images.ImageIndex;

/** The desktop window that starts and stops the server, without which a player who closes their tab has no sign Forge still runs. */
public final class ServerConsole implements IProgressBar {
    private static final Localizer TEXT = Localizer.getInstance();
    /** Card loading prints tens of thousands of lines, so the text is fed on a timer rather than per line. */
    private static final int FLUSH_MILLIS = 250;
    /** How wide a line of text runs before it wraps, which sets the width of the window. */
    private static final int TEXT_WIDTH = 480;
    private static final int HELP_WIDTH = TEXT_WIDTH - 26;
    private static final String GUIDE = "https://github.com/Card-Forge/forge/wiki/Network-Play#network-configuration";
    /** Adapters the rest of the home network cannot reach, though one may be this machine's route to the internet and so be listed first. */
    private static final Pattern NOT_HOME = Pattern.compile("vpn|virtual|hamachi|zerotier|tailscale|wireguard|hyper-v|vmware|wsl|bluetooth",
            Pattern.CASE_INSENSITIVE);

    /** Running is a filled lamp, stopped an empty ring: the shape carries it, so the colour need not. */
    private static final Color LIT = new Color(0x3f, 0xb9, 0x50);
    private static final Color DARK = new Color(0xd0, 0x57, 0x4e);

    private final WebGuiBase ui;
    private final Runnable onQuit;
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
    private final JButton copyNet = copyButton(TEXT.getMessage("lblWebConsoleCopyLink"),
            () -> service.throughCloudflare() ? service.tunnelUrl() : service.inviteUrl(internet));
    private final JToggleButton byRouter = new JToggleButton(TEXT.getMessage("lblWebConsoleByRouter"));
    private final JToggleButton byCloudflare = new JToggleButton(TEXT.getMessage("lblWebConsoleByCloudflare"));
    private final Light tunnelLight = new Light();
    private final JTextArea tunnelState = wrapped("", UIManager.getColor("Label.foreground"), TEXT_WIDTH - 20);
    private final Light picturesLight = new Light(WARN);
    private final JLabel picturesState = new JLabel();
    private final JLabel picturesUpdated = muted(" ");
    private final JProgressBar picturesBar = new JProgressBar(0, 100);
    private final JButton picturesButton = new JButton();
    private final ImageIndex index = new ImageIndex(now -> SwingUtilities.invokeLater(() -> showPictures(now)));
    private final JCheckBox quitWhenEmpty = new JCheckBox(TEXT.getMessage("lblWebConsoleQuitWhenEmpty"), !WebService.KEEP_OPEN);
    private final JCheckBox forwardPort = new JCheckBox(TEXT.getMessage("lblWebConsoleForwardPort"));
    private final Light forwardLight = new Light();
    private final JTextArea forwardState = wrapped("", UIManager.getColor("Label.foreground"), TEXT_WIDTH - 250);
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
    private LogCapture capture;
    private JButton browse;
    private JButton startStop;
    private JButton retryNet;
    private JButton detailsToggle;
    private JPanel startHelp;
    private JPanel forwardStateRow;
    private JPanel forwardHelp;
    private JPanel netField;
    private JPanel tunnelStateRow;
    private JPanel tunnelMissing;
    private JPanel tunnelFailed;
    private JPanel picturesAsk;
    private CloudflareTunnel.State tunnelling = CloudflareTunnel.State.OFF;
    private boolean downloadingIndex;
    private int widest;
    private JPanel main;
    private volatile boolean quitting;

    private ServerConsole(final WebGuiBase ui, final Runnable onQuit) {
        this.ui = ui;
        this.onQuit = onQuit;
    }

    /** Opens the console, or returns null when there is no display or the flag turns it off. */
    public static ServerConsole open(final WebGuiBase ui, final Runnable onQuit) {
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
        console.capture.captureOutput();
        return console;
    }

    /** Names the step of the start-up that is running. */
    public void starting(final String what) {
        SwingUtilities.invokeLater(() -> busy(what));
    }

    /** The server has been started, and this is what the buttons drive from here on. failure is why it could not be, or null. */
    public void attach(final WebService driven, final Exception failure) {
        service = driven;
        images.ready();
        SwingUtilities.invokeLater(() -> {
            quitWhenEmpty.setEnabled(true);
            forwardPort.setEnabled(true);
            imagesButton.setEnabled(true);
            byRouter.setEnabled(true);
            byCloudflare.setEnabled(true);
            picturesButton.setEnabled(true);
            // Asked once, as desktop and mobile ask, and only of a host who has never had the data
            picturesAsk.setVisible(CdnUuidCache.shouldPromptForBulkSync());
            showPictures(new ImageIndex.State(false, -1, false));
        });
        driven.onForwarding(this::forwarding);
        driven.onTunnel(this::tunnelling);
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
            showInternet(null, TEXT.getMessage(service.throughCloudflare() ? "lblWebConsoleCloudflareConnecting" : "lblWebMatchBarWorkingOutAddress"), false);
            if (tunnelling == CloudflareTunnel.State.CONNECTED) {
                tunnelling(tunnelling);
            }
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
            if (!service.throughCloudflare()) {
                findInternet();
            }
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
            final boolean viaRouter = !service.throughCloudflare();
            forwardPort.setSelected(wanted);
            forwardLight.lit(now == WebService.Forwarding.FORWARDED);
            // A refusal only says UPnP did not do it; the port may still be forwarded by hand, so the link stays usable
            refused.setText(TEXT.getMessage("lblWebConsoleRefused", service.port()));
            forwardHelp.setVisible(viaRouter && now == WebService.Forwarding.REFUSED);
            final String words = switch (now) {
                case OFF -> wanted ? "" : TEXT.getMessage("lblWebConsoleUpnpOff", service.port());
                case ASKING -> TEXT.getMessage("lblWebConsoleAskingRouter");
                case FORWARDED -> TEXT.getMessage("lblWebConsoleForwarded", service.port());
                case REFUSED -> "";
            };
            // Forwarding is off until the host asks for it, so the line that says so stands out
            final boolean off = now == WebService.Forwarding.OFF;
            forwardState.setForeground(off ? WARN : UIManager.getColor("Label.foreground"));
            forwardState.setFont(UIManager.getFont("Label.font").deriveFont(off ? Font.BOLD : Font.PLAIN));
            reword(forwardState, words, TEXT_WIDTH - 250);
            forwardLight.setVisible(!words.isEmpty());
            forwardStateRow.setVisible(viaRouter);
            refit();
        });
    }

    /** Shows where the connection through Cloudflare stands, and puts its address where the router's would be. */
    private void tunnelling(final CloudflareTunnel.State now) {
        SwingUtilities.invokeLater(() -> {
            tunnelling = now;
            final boolean cloudflare = service.throughCloudflare();
            byRouter.setSelected(!cloudflare);
            byCloudflare.setSelected(cloudflare);
            tunnelMissing.setVisible(now == CloudflareTunnel.State.MISSING);
            tunnelFailed.setVisible(now == CloudflareTunnel.State.FAILED);
            netField.setVisible(now != CloudflareTunnel.State.MISSING && now != CloudflareTunnel.State.FAILED);
            tunnelLight.lit(now == CloudflareTunnel.State.CONNECTED);
            tunnelLight.setVisible(now == CloudflareTunnel.State.CONNECTED);
            final String words = switch (now) {
                case CONNECTING -> TEXT.getMessage("lblWebConsoleCloudflareWait");
                case CONNECTED -> TEXT.getMessage("lblWebConsoleCloudflareConnected");
                default -> "";
            };
            reword(tunnelState, words, TEXT_WIDTH - 20);
            tunnelStateRow.setVisible(!words.isEmpty());
            if (cloudflare && up) {
                final String url = now == CloudflareTunnel.State.CONNECTED ? service.tunnelUrl() : null;
                // The address without its token, as the other links are shown
                showInternet(url == null ? null : url.substring(0, url.indexOf("/?")), TEXT.getMessage("lblWebConsoleCloudflareConnecting"), false);
            } else if (up) {
                showInternet(null, TEXT.getMessage("lblWebMatchBarWorkingOutAddress"), false);
                inBackground("ForgeAddresses", this::findInternet);
            }
            refit();
        });
    }

    /** Chooses how players on the internet get in, off the event thread because closing either way waits on it. */
    private void choose(final boolean cloudflare) {
        byRouter.setSelected(!cloudflare);
        byCloudflare.setSelected(cloudflare);
        if (cloudflare == service.throughCloudflare() && !(cloudflare && tunnelling == CloudflareTunnel.State.FAILED)) {
            return;
        }
        if (service.playersHere() > 1 && !confirmed("lblWebConsoleSwitchAsk", "lblWebMenuChange")) {
            byRouter.setSelected(!service.throughCloudflare());
            byCloudflare.setSelected(service.throughCloudflare());
            return;
        }
        router.execute(() -> {
            service.throughCloudflare(cloudflare);
            forwarding(service.forwarding());
        });
    }

    /** Asks where Cloudflare's program is, for a host who did not put it where its installer does. */
    private void locateCloudflared() {
        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(TEXT.getMessage("lblWebConsoleCloudflareLocate"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            service.cloudflaredAt(chooser.getSelectedFile());
        }
    }

    /** Says where card pictures come from, which is Scryfall's image servers once its card data has been downloaded. */
    private void showPictures(final ImageIndex.State now) {
        final long updated = ImageIndex.updated();
        picturesBar.setVisible(now.downloading());
        picturesBar.setIndeterminate(now.percent() < 0);
        picturesBar.setValue(Math.max(0, now.percent()));
        picturesLight.lit(updated > 0);
        final String words;
        final String action;
        if (now.downloading()) {
            words = now.percent() < 0 ? TEXT.getMessage("lblWebConsolePicturesStarting") : TEXT.getMessage("lblWebConsolePicturesDownloading", now.percent());
            action = TEXT.getMessage("lblCancel");
            picturesAsk.setVisible(false);
        } else if (now.failed()) {
            words = TEXT.getMessage("lblWebConsolePicturesFailed");
            action = TEXT.getMessage("lblTryAgain");
        } else if (updated > 0) {
            words = TEXT.getMessage("lblWebConsolePicturesFast");
            action = TEXT.getMessage("lblWebImagesIndexUpdate");
        } else {
            words = TEXT.getMessage("lblWebConsolePicturesSlow");
            action = TEXT.getMessage("lblWebConsolePicturesSpeedUp");
        }
        picturesButton.setText(action);
        // The words take what the button leaves, so the row is as wide as the rest whatever the button says
        picturesState.setText("<html>" + words + "</html>");
        // A label wraps HTML only when told how wide it is, and a width given in the HTML itself is not in screen pixels
        final View laidOut = (View) picturesState.getClientProperty(BasicHTML.propertyKey);
        final int room = TEXT_WIDTH - 60 - picturesButton.getPreferredSize().width;
        laidOut.setSize(room, 0);
        final Dimension size = new Dimension(room, (int) Math.ceil(laidOut.getPreferredSpan(View.Y_AXIS)));
        picturesState.setPreferredSize(size);
        picturesState.setMaximumSize(size);
        downloadingIndex = now.downloading();
        picturesUpdated.setText(updated > 0 && !now.downloading()
                ? TEXT.getMessage("lblWebConsolePicturesUpdated", DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(updated))) + "  " : "");
        refit();
    }

    /** The host's answer to the one-time offer, which is not made again whichever it is. */
    private void answerPictures(final boolean download) {
        CdnUuidCache.markBulkSyncPromptAnswered();
        picturesAsk.setVisible(false);
        if (download) {
            index.download();
        }
        refit();
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
            netField.setVisible(true);
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

    /** Zips the network logs and what this window shows into the Downloads folder, as desktop's Help menu does. */
    private void exportLogs() {
        // The links carry the tokens that let a browser in, and a log is sent on to other people
        final String shown = text.getText().replaceAll("token=\\w+", "token=...");
        inBackground("WebExportLogs", () -> {
            String outcome;
            try {
                // This window's output is in no file, and the exporter takes every forge*.log in the user folder
                Files.writeString(Path.of(ForgeProfileProperties.getUserDir(), "forge-server-console.log"), shown);
                File downloads = new File(System.getProperty("user.home"), "Downloads");
                if (!downloads.isDirectory()) {
                    downloads = new File(System.getProperty("user.home"));
                }
                final File zip = LogExporter.exportLogs(downloads);
                outcome = zip == null ? TEXT.getMessage("lblNoLogFilesFound") : TEXT.getMessage("lblSuccess") + "\n" + zip.getAbsolutePath();
            } catch (final IOException e) {
                outcome = e.toString();
            }
            final String say = outcome;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, say, TEXT.getMessage("lblExportLogs"), JOptionPane.INFORMATION_MESSAGE));
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
            if (up && !service.throughCloudflare()) {
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
        forwardStateRow = row(forwardLight, Box.createHorizontalStrut(6), forwardState, Box.createHorizontalGlue(), forwardPort);
        forwardLight.setVisible(false);
        refused.setForeground(WARN);
        refused.setFont(refused.getFont().deriveFont(Font.BOLD));
        forwardHelp = warning(refused, wrapped(TEXT.getMessage("lblWebConsoleRefusedAbout"), WARN_TEXT, HELP_WIDTH),
                row(button(TEXT.getMessage("lblWebConsoleUseCloudflare"), () -> choose(true)), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblTryAgain"), () -> router.execute(() -> service.forwardPort(true))), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblWebConsoleForwardedMyself"), () -> router.execute(() -> service.forwardPort(false))),
                        Box.createHorizontalStrut(12), Box.createHorizontalGlue(), link(TEXT.getMessage("lblWebConsoleSetupGuide"), this::openGuide)));
        forwardHelp.setVisible(false);

        final ButtonGroup ways = new ButtonGroup();
        ways.add(byRouter);
        ways.add(byCloudflare);
        byRouter.setSelected(true);
        byRouter.setEnabled(false);
        byCloudflare.setEnabled(false);
        byRouter.addActionListener(e -> choose(false));
        byCloudflare.addActionListener(e -> choose(true));
        tunnelStateRow = row(tunnelLight, Box.createHorizontalStrut(6), tunnelState);
        tunnelStateRow.setVisible(false);
        tunnelMissing = note(heading(TEXT.getMessage("lblWebConsoleCloudflareMissing"), NOTE),
                wrapped(TEXT.getMessage("lblWebConsoleCloudflareMissingAbout"), WARN_TEXT, HELP_WIDTH),
                row(button(TEXT.getMessage("lblWebConsoleCloudflareGet"), () -> open(CloudflareTunnel.DOWNLOADS)), Box.createHorizontalStrut(10),
                        muted(TEXT.getMessage("lblWebConsoleCloudflareWaiting")), Box.createHorizontalGlue(),
                        link(TEXT.getMessage("lblWebConsoleCloudflareElsewhere"), this::locateCloudflared)));
        tunnelMissing.setVisible(false);
        tunnelFailed = warning(heading(TEXT.getMessage("lblWebConsoleCloudflareFailed"), WARN),
                wrapped(TEXT.getMessage("lblWebConsoleCloudflareFailedAbout"), WARN_TEXT, HELP_WIDTH),
                row(button(TEXT.getMessage("lblTryAgain"), () -> choose(true)), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblWebConsoleUseRouter"), () -> choose(false))));
        tunnelFailed.setVisible(false);

        picturesBar.setVisible(false);
        picturesBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        picturesButton.setEnabled(false);
        picturesButton.addActionListener(e -> {
            if (downloadingIndex) {
                index.cancel();
            } else {
                answerPictures(true);
            }
        });
        picturesAsk = note(heading(TEXT.getMessage("lblWebConsolePicturesAsk"), NOTE),
                wrapped(TEXT.getMessage("lblWebConsolePicturesAskAbout"), WARN_TEXT, HELP_WIDTH),
                row(button(TEXT.getMessage("lblWebConsolePicturesDownloadNow"), () -> answerPictures(true)), Box.createHorizontalStrut(8),
                        button(TEXT.getMessage("lblNotNow"), () -> answerPictures(false))));
        picturesAsk.setVisible(false);

        quitWhenEmpty.setEnabled(false);
        quitWhenEmpty.addActionListener(e -> service.quitWhenEmpty(quitWhenEmpty.isSelected()));
        imagesButton.addActionListener(e -> images.show(frame));
        imagesButton.setEnabled(false);
        asLink(imagesButton);
        detailsToggle = link("", () -> showDetails(!details.isVisible()));
        labelDetails();

        main = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                final Dimension size = super.getPreferredSize();
                size.width = Math.max(size.width, widest);
                return size;
            }
        };
        main.setLayout(new BoxLayout(main, BoxLayout.PAGE_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(14, 26, 12, 26));
        final Color quiet = UIManager.getColor("Label.disabledForeground");
        section(main, picturesAsk,
                row(light, Box.createHorizontalStrut(10), stack(row(state, Box.createHorizontalStrut(10), players), keepOpen),
                        Box.createHorizontalGlue(), browse, Box.createHorizontalStrut(8), startStop),
                progress, startHelp);
        netField = field(netAddress, Box.createHorizontalGlue(), retryNet, Box.createHorizontalStrut(8), copyNet);
        section(main, stack(heading(TEXT.getMessage("lblWebConsoleInvitePlayers")), wrapped(TEXT.getMessage("lblWebConsoleInviteAbout"), quiet, TEXT_WIDTH)),
                heading(TEXT.getMessage("lblWebConsoleOnNetwork")),
                field(homeAddress, Box.createHorizontalGlue(), copyHome, moreHome),
                row(heading(TEXT.getMessage("lblWebConsoleOnInternet")), Box.createHorizontalStrut(12), byRouter, byCloudflare),
                netField, forwardStateRow, forwardHelp, tunnelStateRow, tunnelMissing, tunnelFailed);
        section(main, field(picturesLight, Box.createHorizontalStrut(10),
                stack(picturesState, stack(picturesBar, row(picturesUpdated, imagesButton))),
                Box.createHorizontalGlue(), picturesButton));
        final JPanel foot = row(quitWhenEmpty, Box.createHorizontalGlue(), detailsToggle);
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
        capture = new LogCapture(text, () -> {
            if (!details.isVisible()) {
                unseenErrors++;
            }
        });

        final JPanel numbers = new JPanel();
        numbers.setLayout(new BoxLayout(numbers, BoxLayout.PAGE_AXIS));
        final JPanel title = row(heading(TEXT.getMessage("lblWebConsoleDiagnostics")), Box.createHorizontalGlue(),
                button(TEXT.getMessage("lblExportLogs"), this::exportLogs), Box.createHorizontalStrut(8),
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

        new Timer(FLUSH_MILLIS, e -> {
            if (capture.flush()) {
                labelDetails();
            }
        }).start();
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
        fit();
        // The space under a part is shown by an event that follows the part's own, so the fit is made again once that has run
        SwingUtilities.invokeLater(this::fit);
    }

    private void fit() {
        // As wide as its widest part, shown or not, so the window keeps one width as help comes and goes
        for (final Component part : main.getComponents()) {
            widest = Math.max(widest, part.getPreferredSize().width + main.getInsets().left + main.getInsets().right);
        }
        // The details stand beside the main column at its width
        details.setPreferredSize(new Dimension(main.getPreferredSize().width, 480));
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
        open(GUIDE);
    }

    private void open(final String address) {
        try {
            ui.browseToUrl(address);
        } catch (final IOException | URISyntaxException e) {
            Logger.warn(e, "Open {} in a browser", address);
        }
    }

    /** A lamp, filled while the server is up and an empty ring while it is not. */
    private static final class Light extends JComponent {
        private static final int SIZE = 11;
        private final Color off;
        private boolean on;

        Light() {
            this(DARK);
        }

        /** off is the colour of the empty ring, for a lamp whose unlit state is not a fault. */
        Light(final Color off) {
            this.off = off;
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
            g2.setColor(on ? LIT : off);
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

    public void close() {
        quitting = true;
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }
}
