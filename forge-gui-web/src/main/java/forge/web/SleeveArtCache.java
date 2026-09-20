package forge.web;

import forge.localinstance.properties.ForgeConstants;
import forge.util.SleeveArt;

import java.io.File;

/**
 * The art crop behind a card-art deck sleeve. The browser crops and frames it, so the server only has to
 * hand over the file the shared fetcher caches.
 */
final class SleeveArtCache {
    private SleeveArtCache() {
    }

    /** The cached crop for a card image key, or null when it has not been downloaded yet. */
    static File file(final String imageKey) {
        if (imageKey == null || imageKey.isEmpty()) {
            return null;
        }
        final File f = new File(ForgeConstants.CACHE_SLEEVE_PICS_DIR, SleeveArt.cacheFileName(imageKey));
        return f.isFile() ? f : null;
    }
}
