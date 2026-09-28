package forge.web;

import forge.deck.Deck;
import forge.gui.GuiBase;

import java.util.function.Function;

/** A match between the web seat, played by a test's browser, and the computer; shut down once the test is done. */
final class TestMatch {
    private TestMatch() {
    }

    interface Body<B> {
        void run(LocalGame local, WebGuiGame gui, B browser) throws Exception;
    }

    static <B extends BrowserChannel> void play(final Deck web, final Deck computer, final Function<WebGuiGame, B> browserFor,
            final Body<B> body) throws Exception {
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final B browser = browserFor.apply(gui);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", web, "AI", computer, gui));
            body.run(local, gui, browser);
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }
}
