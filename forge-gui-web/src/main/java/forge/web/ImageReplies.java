package forge.web;

import forge.ImageKeys;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.util.ImageFetcher;
import forge.web.images.CardThumbnails;
import forge.web.images.Foil;
import forge.web.images.ImageFiles;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static forge.web.WebServer.KEEP_FOREVER;
import static forge.web.WebServer.notFound;
import static forge.web.WebServer.respond;

/** Answers the requests for card pictures and sleeve art. A picture that is not saved yet is downloaded first. */
final class ImageReplies implements IHasForgeLog {
    private ImageReplies() {
    }

    /**
     * Never waits for the download. Otherwise the thread that serves every other request would wait too.
     * auto means the page asked on its own, not the player, so art that is not cached yet is downloaded only when online image fetching is on.
     */
    static void serveSleeveArt(final ChannelHandlerContext ctx, final String asked, final boolean auto) {
        final String key = ImageFiles.artKey(asked);
        final File cached = ImageFiles.sleeveArt(key);
        if (cached != null) {
            respondImage(ctx, cached);
            return;
        }
        // Only a card has art to crop, not a token or an effect such as the Ring
        if (!key.startsWith(ImageKeys.CARD_PREFIX) || auto && !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            notFound(ctx);
            return;
        }
        fetch(true, key, () -> {
            final File fetched = ImageFiles.sleeveArt(key);
            if (fetched != null) {
                respondImage(ctx, fetched);
            } else {
                notFound(ctx);
            }
        }, () -> notFound(ctx));
    }

    /** Downloads a missing image and answers the request when it arrives. small asks for the card at the size the board draws it. */
    static void serveImage(final ChannelHandlerContext ctx, final String key, final boolean small) {
        final int foil = Foil.seed(key);
        final File file = ImageFiles.card(key);
        if (file != null) {
            respondCardImage(ctx, file, small, foil);
            return;
        }
        if (!ImageFiles.safeKey(key) || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            notFound(ctx);
            return;
        }
        fetch(false, key, () -> {
            // Forge remembers a picture it could not find. Without this it would still report the picture as missing now that it exists
            ImageKeys.clearMissingCards();
            final File fetched = ImageFiles.card(key);
            if (fetched != null) {
                respondCardImage(ctx, fetched, small, foil);
            } else {
                notFound(ctx);
            }
        }, () -> notFound(ctx));
    }

    /** Starts a download on the UI thread. Then one of arrived and missing runs, on that thread. */
    private static void fetch(final boolean sleeveArt, final String key, final Runnable arrived, final Runnable missing) {
        GuiBase.getInterface().invokeInEdtLater(() -> {
            final ImageFetcher.Callback answer = new ImageFetcher.Callback() {
                @Override
                public void onImageFetched() {
                    arrived.run();
                }

                @Override
                public void onNoImageFetched() {
                    missing.run();
                }
            };
            if (sleeveArt) {
                GuiBase.getInterface().getImageFetcher().fetchSleeveArt(key, answer);
            } else {
                GuiBase.getInterface().getImageFetcher().fetchImage(key, answer);
            }
        });
    }

    private static void respondCardImage(final ChannelHandlerContext ctx, final File file, final boolean small, final int foil) {
        if (!small && foil == 0) {
            respondImage(ctx, file);
            return;
        }
        CardThumbnails.of(file, small, foil).whenComplete((bytes, failed) -> {
            if (bytes != null) {
                respond(ctx, HttpResponseStatus.OK, bytes, ImageFiles.type(file), null, KEEP_FOREVER, null);
                return;
            }
            netLog.warn("Could not draw {}: {}", file, failed.getMessage());
            respondImage(ctx, file);
        });
    }

    private static void respondImage(final ChannelHandlerContext ctx, final File file) {
        try {
            respond(ctx, HttpResponseStatus.OK, Files.readAllBytes(file.toPath()), ImageFiles.type(file), null, KEEP_FOREVER, null);
        } catch (final IOException e) {
            netLog.warn("Could not send {}: {}", file, e.getMessage());
            notFound(ctx);
        }
    }
}
