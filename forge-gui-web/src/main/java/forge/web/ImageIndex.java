package forge.web;

import forge.gui.FThreads;
import forge.gui.download.CdnUuidCache;
import forge.gui.download.ScryfallBulkDataSync;

import java.io.File;
import java.util.function.Consumer;

/**
 * Scryfall's card data file, which holds the direct address of every card picture on its image servers. With it Forge
 * fetches from those servers, which have no rate limit, in place of Scryfall's API, which has one.
 */
final class ImageIndex {
    /** percent is how far a download has got, or -1 when none is running or its size is not yet known. */
    record State(boolean downloading, int percent, boolean failed) { }

    private final Consumer<State> listener;
    private volatile boolean cancelled;
    private volatile boolean downloading;

    /** The listener is told of every change, on the thread the download runs on. */
    ImageIndex(final Consumer<State> listener) {
        this.listener = listener;
    }

    /** When the data was last downloaded, from its newest set file, or 0 when it never was. */
    static long updated() {
        // The set files only: the folder also holds a marker saying the offer to download it was answered
        final File[] files = new File(CdnUuidCache.cacheDir()).listFiles((dir, name) -> name.endsWith(".json.gz"));
        long newest = 0;
        for (final File f : files == null ? new File[0] : files) {
            newest = Math.max(newest, f.lastModified());
        }
        return newest;
    }

    void download() {
        if (downloading) {
            return;
        }
        downloading = true;
        cancelled = false;
        listener.accept(new State(true, -1, false));
        FThreads.invokeInBackgroundThread(() -> {
            final int sets = ScryfallBulkDataSync.sync(ScryfallBulkDataSync.BULK_TYPE_DEFAULT_CARDS, null,
                    (message, fraction) -> listener.accept(new State(true, fraction < 0 ? -1 : (int) (fraction * 100), false)),
                    () -> cancelled);
            downloading = false;
            listener.accept(new State(false, -1, sets < 0 && !cancelled));
        });
    }

    void cancel() {
        cancelled = true;
    }
}
