package forge.web;

import com.google.gson.JsonObject;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IProgressBar;
import forge.gui.interfaces.ITextField;
import forge.localinstance.skin.FSkinProp;
import forge.gui.UiCommand;
import org.tinylog.Logger;

import java.util.function.Consumer;

/**
 * Runs a download the way desktop and mobile do, but with the browser as the progress window: the shared
 * service does the work and reports through the same interfaces their dialogs implement.
 */
final class WebDownloads {
    private WebDownloads() {
    }

    static void run(final GuiDownloadService service, final Consumer<Boolean> callback,
            final Consumer<JsonObject> toBrowser) {
        final Progress progress = new Progress(service.getTitle(), toBrowser);
        try {
            // The desktop dialog wires the service up and waits for the user to press Start and then Close.
            // Nothing here does either, so the service is set up and then run on this thread to completion.
            service.initialize(new Text(""), new Text(""), progress, new StartButton(), () -> { }, null, () -> { });
            service.run();
        } catch (final RuntimeException e) {
            Logger.error(e, "Download failed");
            progress.say("Could not download.");
            callback.accept(false);
            return;
        }
        progress.finish();
        callback.accept(true);
    }

    /** Reports where a download has got to, as a notice the browser already knows how to show. */
    private static final class Progress implements IProgressBar {
        private final String title;
        private final Consumer<JsonObject> toBrowser;
        private String description;
        private int maximum = 1;

        Progress(final String title, final Consumer<JsonObject> toBrowser) {
            this.title = title;
            this.toBrowser = toBrowser;
        }

        void finish() {
            say(description == null ? "Finished" : description);
        }

        void say(final String text) {
            final JsonObject notice = JsonCodec.message("notice");
            notice.addProperty("title", title);
            notice.addProperty("message", text);
            notice.addProperty("error", false);
            toBrowser.accept(notice);
        }

        @Override public void setDescription(final String s) {
            description = s;
            say(s);
        }

        @Override public void setValue(final int progress) {
        }

        @Override public void reset() {
        }

        @Override public void setShowETA(final boolean b) {
        }

        @Override public void setShowCount(final boolean b) {
        }

        @Override public void setPercentMode(final boolean percentMode) {
        }

        @Override public void setMaximum(final int maximum0) {
            maximum = maximum0;
        }

        @Override public int getMaximum() {
            return maximum;
        }
    }

    /** Holds the command the service assigns, so it can be run without anyone clicking. */
    private static final class StartButton implements IButton {
        private UiCommand command;
        private boolean enabled;

        void press() {
            if (command != null && enabled) {
                command.run();
            }
        }

        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(final boolean b) { enabled = b; }
        @Override public boolean isVisible() { return true; }
        @Override public void setVisible(final boolean b) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(final String s) { }
        @Override public String getText() { return ""; }
        @Override public void setText(final String text) { }
        @Override public void setSelected(final boolean b) { }
        @Override public boolean isSelected() { return false; }
        @Override public void setCommand(final UiCommand command0) { command = command0; }
        @Override public void setImage(final FSkinProp color) { }
        @Override public void setTextColor(final int r, final int g, final int b) { }
        @Override public boolean requestFocusInWindow() { return false; }
    }

    /** The address and port fields a download dialog carries; a zip service reads them and nothing else. */
    private record Text(String value) implements ITextField {
        @Override public String getText() { return value; }
        @Override public void setText(final String text) { }
        @Override public boolean isEnabled() { return false; }
        @Override public void setEnabled(final boolean b) { }
        @Override public boolean isVisible() { return true; }
        @Override public void setVisible(final boolean b) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(final String s) { }
        @Override public boolean requestFocusInWindow() { return false; }
    }
}
