package forge.web;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Card images at the size the board and hand draw them. A browser shrinking a 488px scan to an 88px card on a
 * rotated or animated layer samples it with a cheap filter and the card's text breaks up; shrunk here with a proper
 * one, the browser has little left to do. Each is made once, off the thread that serves every connection.
 */
final class CardThumbnails {
    /** Twice a board card's width, so it stays sharp on a screen scaled up to 2.5 times. */
    static final int WIDTH = 256;
    private static final int MOST_KEPT = 600;
    private static final float JPEG_QUALITY = 0.88f;

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "CardThumbnails");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, byte[]> KEPT = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, byte[]> eldest) {
            return size() > MOST_KEPT;
        }
    });

    private CardThumbnails() {
    }

    /** The image shrunk to WIDTH, in its own format; an image already that narrow comes back as it is. */
    static CompletableFuture<byte[]> of(final File file) {
        final String key = file.getAbsolutePath() + ':' + file.lastModified();
        final byte[] kept = KEPT.get(key);
        if (kept != null) {
            return CompletableFuture.completedFuture(kept);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                final byte[] made = shrink(file);
                KEPT.put(key, made);
                return made;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }, WORKERS);
    }

    private static byte[] shrink(final File file) throws IOException {
        final BufferedImage source = ImageIO.read(file);
        if (source == null) {
            throw new IOException("Not an image: " + file);
        }
        final boolean png = file.getName().toLowerCase(Locale.ROOT).endsWith(".png");
        BufferedImage image = source;
        // Halving at a time with bilinear sampling reads every source pixel, which one large step would skip
        while (image.getWidth() > WIDTH) {
            final int w = Math.max(WIDTH, image.getWidth() / 2);
            final int h = Math.max(1, Math.round(image.getHeight() * (float) w / image.getWidth()));
            image = scaled(image, w, h, png);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (png) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage scaled(final BufferedImage from, final int w, final int h, final boolean alpha) {
        final BufferedImage to = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = to.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(from, 0, 0, w, h, null);
        g.dispose();
        return to;
    }
}
