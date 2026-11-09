package forge.web.images;

import forge.gui.FThreads;
import forge.localinstance.properties.ForgeConstants;
import forge.util.IHasForgeLog;
import forge.util.ImageFetcher;
import forge.util.TextUtil;

import java.io.IOException;

/**
 * Downloads missing card images into the shared image cache, like the desktop and mobile fetchers. It saves the bytes as they
 * arrive, because the browser decodes the picture itself. So neither desktop's ImageIO re-encoding nor mobile's FileHandle writing fits.
 */
public final class WebImageFetcher extends ImageFetcher implements IHasForgeLog {
    @Override
    protected Runnable getDownloadTask(final String[] downloadUrls, final String destPath, final Runnable notifyObservers) {
        return () -> {
            boolean saved = false;
            try {
                for (final String url : downloadUrls) {
                    saved = fetch(url.replace("PLANECHASEBG:", ""), destPath);
                    if (saved) {
                        break;
                    }
                }
            } finally {
                if (saved) {
                    FThreads.invokeInEdtLater(notifyObservers);
                } else {
                    downloadFailed(destPath);
                }
            }
        };
    }

    /** Like desktop, a token address that fails to connect is tried again without its set code. */
    private boolean fetch(final String url, final String destPath) {
        try {
            return download(url, savedAt(url, destPath));
        } catch (final IOException e) {
            netLog.warn("Card image download failed for {}: {}", url, e.getMessage());
        }
        final int set = url.lastIndexOf('_');
        final int type = url.lastIndexOf('.');
        if (!url.contains("tokens") || set < 0 || set > type) {
            return false;
        }
        final String setless = url.substring(0, set) + url.substring(type);
        try {
            return download(setless, savedAt(setless, destPath));
        } catch (final IOException e) {
            netLog.warn("Card image download failed for {}: {}", setless, e.getMessage());
            return false;
        }
    }

    /** Scryfall's pictures have a border, so they are saved under the .fullborder name that Forge looks for first. */
    private static String savedAt(final String url, final String destPath) {
        final boolean scryfall = url.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) || url.startsWith(ForgeConstants.URL_SCRYFALL_CDN);
        final String path = url.contains(".fullborder.") || scryfall ? TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
        // An art crop keeps its name, because renaming it to .fullborder hides it from the sleeve cache
        if (!path.contains(".full") && !path.contains(".artcrop") && scryfall
                && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR) && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR)) {
            return path.replace(".jpg", ".fullborder.jpg");
        }
        return path;
    }
}
