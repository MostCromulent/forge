package forge.util;

import forge.localinstance.properties.ForgeConstants;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class SwingImageFetcher extends ImageFetcher {

    @Override
    protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
        return new SwingDownloadTask(downloadUrls, destPath, notifyObservers);
    }

    private static class SwingDownloadTask implements Runnable {
        private final String[] downloadUrls;
        private final String destPath;
        private final Runnable notifyObservers;

        public SwingDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
            this.downloadUrls = downloadUrls;
            this.destPath = destPath;
            this.notifyObservers = notifyObservers;
        }

        private boolean doFetch(String urlToDownload) throws IOException {
            if (destPath.startsWith(ForgeConstants.CACHE_SLEEVE_PICS_DIR)) {
                return doFetchSleeve(urlToDownload);
            }
            if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
                // Don't try to download card images from cardforge servers
                return false;
            }

            String newdespath = urlToDownload.contains(".fullborder.jpg") || urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) ?
                    TextUtil.fastReplace(destPath, ".full.jpg", ".fullborder.jpg") : destPath;
            if (!newdespath.contains(".full") && !newdespath.contains(".artcrop") && urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR))
                newdespath = newdespath.replace(".jpg", ".fullborder.jpg"); //fix planes/phenomenon for round border options
            URL url = new URL(urlToDownload);
            System.out.println("Attempting to fetch: " + url);
            BufferedImage image = ImageIO.read(url);
            // First, save to a temporary file so that nothing tries to read
            // a partial download.
            File destFile = new File(newdespath + ".tmp");
            // need to check directory folder for mkdir
            destFile.getParentFile().mkdirs();
            if (ImageIO.write(image, "jpg", destFile)) {
                // Now, rename it to the correct name.
                if (destFile.renameTo(new File(newdespath))) {
                    System.out.println("Saved image to " + newdespath);
                    SwingUtilities.invokeLater(notifyObservers);
                } else {
                    System.err.println("Failed to rename image to " + newdespath);
                    return false;
                }
            } else {
                System.err.println("Failed to save image from " + url + " as jpeg");
                // try to save image as png instead
                if (ImageIO.write(image, "png", destFile)) {
                    String newPath = newdespath.replace(".jpg", ".png");
                    if (destFile.renameTo(new File(newPath))) {
                        System.out.println("Saved image to " + newPath);
                        SwingUtilities.invokeLater(notifyObservers);
                    } else {
                        System.err.println("Failed to rename image to " + newPath);
                    }
                } else {
                    System.err.println("Failed to save image from " + url + " as png");
                }
                return false;
            }

            return true;
        }

        private boolean doFetchSleeve(String urlToDownload) throws IOException {
            if (!CustomSleeves.isHttps(urlToDownload)) {
                return false;
            }
            final HttpURLConnection conn = (HttpURLConnection) new URL(urlToDownload).openConnection();
            conn.setConnectTimeout(CustomSleeves.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CustomSleeves.READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            final File tmp = new File(destPath + ".tmp");
            try {
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return false;
                }
                if (conn.getContentLengthLong() > CustomSleeves.MAX_DOWNLOAD_BYTES) {
                    return false;
                }
                tmp.getParentFile().mkdirs();
                long total = 0;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > CustomSleeves.MAX_DOWNLOAD_BYTES) {
                            return false;
                        }
                        out.write(buf, 0, n);
                    }
                }
                final Dimension dim = readImageDimensions(tmp);
                if (dim == null || !CustomSleeves.withinDimensionLimit(dim.width, dim.height)) {
                    return false;
                }
                final BufferedImage img = ImageIO.read(tmp);
                if (img == null) {
                    return false;
                }
                ImageIO.write(img, "png", new File(destPath));
                SwingUtilities.invokeLater(notifyObservers);
                return true;
            } finally {
                conn.disconnect();
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
        }

        private static Dimension readImageDimensions(final File file) {
            try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
                final Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    final ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis);
                        return new Dimension(reader.getWidth(0), reader.getHeight(0));
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (IOException ignored) {
            }
            return null;
        }

        private String tofullBorder(String imageurl) {
            if (!imageurl.contains(".full.jpg"))
                return imageurl;
            try {
                URL url = new URL(imageurl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                //connection.setConnectTimeout(1000 * 5); //wait 5 seconds the most
                //connection.setReadTimeout(1000 * 5);
                conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
                if(conn.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND)
                    imageurl = TextUtil.fastReplace(imageurl, ".full.jpg", ".fullborder.jpg");
                conn.disconnect();
                return imageurl;
            } catch (IOException ex) {
                return imageurl;
            }
        }

        public void run() {
            boolean success = false;
            for (String urlToDownload : downloadUrls) {
                try {
                    if (doFetch(urlToDownload)) {
                        success = true;
                        break;
                    }
                } catch (IOException e) {
                    System.err.println("Failed to download card [" + destPath + "] image: " + e.getMessage());
                    if (urlToDownload.contains("tokens")) {
                        int setIndex = urlToDownload.lastIndexOf('_');
                        int typeIndex = urlToDownload.lastIndexOf('.');
                        String setlessFilename = urlToDownload.substring(0, setIndex);
                        String extension = urlToDownload.substring(typeIndex);
                        urlToDownload = setlessFilename+extension;
                        try {
                            if (doFetch(urlToDownload)) {
                                success = true;
                                break;
                            }
                        } catch (IOException t) {
                            System.err.println("Failed to download setless token [" + destPath + "]: " + e.getMessage());
                        }
                    }
                }
            }
            // If all downloads fail, mark this image as unfetchable so we don't try again.
        }
    }

}
