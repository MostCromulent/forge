package forge.web;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageFetcher;
import forge.util.ScryfallRateLimiter;
import forge.util.TextUtil;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Downloads missing card images into the shared image cache, like the desktop and mobile fetchers. */
final class WebImageFetcher extends ImageFetcher {
    @Override
    protected Runnable getDownloadTask(final String[] downloadUrls, final String destPath, final Runnable notifyObservers) {
        return () -> {
            for (final String url : downloadUrls) {
                try {
                    if (download(url.replace("PLANECHASEBG:", ""), destPath)) {
                        GuiBase.getInterface().invokeInEdtLater(notifyObservers);
                        return;
                    }
                } catch (final IOException e) {
                    Logger.warn("Card image download failed for {}: {}", url, e.getMessage());
                }
            }
        };
    }

    // The browser decodes JPEG and PNG itself, so the file is saved as downloaded
    private static boolean download(final String urlToDownload, final String destPath) throws IOException {
        if ((disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) || ScryfallRateLimiter.shouldSkip(urlToDownload)) {
            return false;
        }
        final boolean scryfall = urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) || urlToDownload.startsWith(ForgeConstants.URL_SCRYFALL_CDN);
        String path = urlToDownload.contains(".fullborder.") || scryfall ? TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
        // An art crop is already named for what it is; renaming it to .fullborder hides it from the sleeve cache
        if (!path.contains(".full") && !path.contains(".artcrop") && scryfall
                && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR) && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR)) {
            // Planes and phenomena use the round-border naming
            path = path.replace(".jpg", ".fullborder.jpg");
        }
        ScryfallRateLimiter.acquire(urlToDownload);
        final HttpURLConnection c = (HttpURLConnection) new URL(urlToDownload).openConnection();
        try {
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            final int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                ScryfallRateLimiter.noteIfRateLimited(code, urlToDownload, c.getHeaderField("Retry-After"));
                return false;
            }
            // Written aside first so nothing reads a partial file
            final Path target = Path.of(path);
            final Path tmp = Path.of(path + ".tmp");
            Files.createDirectories(target.getParent());
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) {
                Files.delete(tmp);
                return false;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            c.disconnect();
        }
    }
}
