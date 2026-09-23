package forge.web;

import com.google.gson.JsonObject;
import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.web.ToBrowser.Notice;
import org.jupnp.UpnpServiceConfiguration;
import org.tinylog.Logger;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Platform services for the web UI. The single "WebUI" thread stands in for Swing's EDT on the host side. */
public final class WebGuiBase implements IGuiBase {
    private volatile Thread uiThread;
    private final ExecutorService ui = Executors.newSingleThreadExecutor(r -> {
        // Must not start with "Game": ThreadUtil.isGameThread is a name-prefix test
        final Thread t = new Thread(r, "WebUI");
        t.setDaemon(true);
        uiThread = t;
        return t;
    });
    private final String assetsDir = resolveAssetsDir();
    private final ImageFetcher imageFetcher = new WebImageFetcher();
    private volatile Consumer<JsonObject> noticeSink = notice -> { };
    private final HostRequests hostRequests = new HostRequests();

    public void setNoticeSink(final Consumer<JsonObject> sink) {
        noticeSink = sink;
        hostRequests.setSink(sink);
    }

    HostRequests hostRequests() {
        return hostRequests;
    }

    static String resolveAssetsDir() {
        final String configured = System.getProperty("forge.assets.dir");
        if (configured != null) {
            return withSlash(new File(configured).getAbsolutePath());
        }
        final File cwd = new File(System.getProperty("user.dir"));
        if (new File(cwd, "res").isDirectory()) {
            return withSlash(cwd.getAbsolutePath());
        }
        // Launched from the repo root or from a module directory
        for (final File candidate : new File[]{new File(cwd, "forge-gui"), new File(cwd.getParentFile(), "forge-gui")}) {
            if (new File(candidate, "res").isDirectory()) {
                return withSlash(candidate.getAbsolutePath());
            }
        }
        return "";
    }

    private static String withSlash(final String path) {
        return path.endsWith(File.separator) ? path : path + File.separator;
    }

    private static Runnable guarded(final Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (final RuntimeException e) {
                Logger.error(e, "WebUI task failed");
            }
        };
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return BuildInfo.getVersionString(); }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return imageFetcher; }
    @Override public boolean isGuiThread() { return Thread.currentThread() == uiThread; }

    @Override
    public void invokeInEdtNow(final Runnable runnable) {
        if (isGuiThread()) {
            runnable.run();
        } else {
            ui.execute(guarded(runnable));
        }
    }

    @Override
    public void invokeInEdtLater(final Runnable runnable) {
        ui.execute(guarded(runnable));
    }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        try {
            ui.submit(proc).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {
        final Thread t = new Thread(guarded(task), "WebBackground");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        // A single-option dialog is a message box (SOptionPane.showMessageDialog), which needs no answer
        if (options == null || options.size() <= 1) {
            noticeSink.accept(Wire.encode(new Notice(title, message, icon == FSkinProp.ICO_ERROR || icon == FSkinProp.ICO_WARNING)));
            return defaultOption;
        }
        final List<Integer> answer = hostRequests.ask("choices", title == null ? message : title + " — " + message,
                options, 1, 1);
        if (answer != null && !answer.isEmpty()) {
            final int picked = answer.get(0);
            if (picked >= 0 && picked < options.size()) {
                return picked;
            }
        }
        Logger.warn("Host dialog answered with its default: {} / {}", title, message);
        return defaultOption;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        Logger.warn("Host input dialog answered with its default: {} / {}", title, message);
        return initialInput;
    }

    /** A choice the host needs outside a match, such as a net deck category. The browser answers it. */
    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices, final Collection<T> selected, final FSerializableFunction<T, String> display) {
        final List<T> all = new ArrayList<>(choices);
        final List<String> options = new ArrayList<>();
        for (final T choice : all) {
            options.add(display == null ? String.valueOf(choice) : display.apply(choice));
        }
        final List<Integer> answer = hostRequests.ask("choices", message, options, min, max);
        final List<T> result = new ArrayList<>();
        if (answer != null) {
            for (final int i : answer) {
                if (i >= 0 && i < all.size()) {
                    result.add(all.get(i));
                }
            }
        }
        // Nobody answered, so fall back to what the host would have done on its own
        if (result.isEmpty() && min > 0) {
            Logger.warn("Host choice unanswered, taking the first: {}", message);
            result.add(all.get(0));
        }
        return result;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
        Logger.warn("Host order dialog answered with its default: {}", title);
        return new ArrayList<>(sourceChoices);
    }

    @Override
    public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        Logger.warn("Host card dialog answered with its default: {}", title);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void browseToUrl(final String url) throws IOException, URISyntaxException {
        final Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (desktop != null) {
            desktop.browse(new URI(url));
        }
    }

    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    // Each browser's session builds its own WebGuiGame; nothing asks GuiBase for one
    @Override public IGuiGame getNewGuiGame() { throw new UnsupportedOperationException("WebGuiGame is created by WebSession"); }
    @Override public boolean hasNetGame() { return false; }

    @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
    @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background, final String overlayFilename, final float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(final boolean preventSleep) { }
    @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        WebDownloads.run(service, callback, noticeSink);
    }
    @Override public void copyToClipboard(final String text) { }
    @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) { Logger.error("{}: {}", title, text); }
    @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
    @Override public String showFileDialog(final String title, final String defaultDir) { return null; }
    @Override public File getSaveFile(final File defaultFile) { return null; }
    // The browser plays the audio, so the formats it understands are the ones this client supports
    @Override public boolean isSupportedAudioFormat(final File file) {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".m4a");
    }
    @Override public IAudioClip createAudioClip(final String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
    @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
}
