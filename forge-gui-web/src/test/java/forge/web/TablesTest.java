package forge.web;

import forge.deck.Deck;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Tests of a table's rules, run through {@link Lobby} at a hosted table. Each test sits at a fresh table, and one host
 * serves the class: stopping the server takes seconds, so it is stopped once, after the last test.
 */
abstract class TablesTest {
    /** Made once the model is loaded, which the netplay server reads as it starts. */
    LocalGame local;

    interface TableTest {
        void run(LocalGame local, Lobby lobby) throws Exception;
    }

    @BeforeClass
    public void startHost() {
        WebTestSupport.initModel();
        local = new LocalGame();
    }

    @AfterClass(alwaysRun = true)
    public void stopHost() {
        if (local != null) {
            onUi(local::shutdown);
        }
    }

    static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    /** A fresh table: the browser's seat and a computer's, neither holding a deck. */
    void atTable(final TableTest body) throws Exception {
        atTable(lobby -> { }, body);
    }

    /** A fresh table whose computer seat holds the given deck. */
    void atTable(final Deck computerDeck, final TableTest body) throws Exception {
        atTable((local, lobby) -> {
            final int computer = computer(local);
            onUi(() -> {
                local.hostedLobby().getSlot(computer).setDeck(computerDeck);
                local.pushLobby();
            });
            body.run(local, lobby);
        });
    }

    /**
     * A fresh table whose lobby answers its own updates as a browser's session does, rebuilding the deck list when the
     * rules change. Updates arrive while a change is still being made, which the plain table does not show.
     */
    void atLiveTable(final TableTest body) throws Exception {
        atTable(lobby -> {
            if (lobby.restrictionsChanged()) {
                lobby.decks();
            }
        }, body);
    }

    /** A fresh table that runs onUpdate with its lobby on every update the table announces. */
    void atTable(final Consumer<Lobby> onUpdate, final TableTest body) throws Exception {
        final WebGuiGame gui = new WebGuiGame();
        final AtomicReference<Lobby> ref = new AtomicReference<>();
        try {
            onUi(() -> local.openHost("Host", gui, () -> {
                final Lobby lobby = ref.get();
                if (lobby != null) {
                    onUpdate.accept(lobby);
                }
            }, (from, text) -> { }));
            ref.set(new Lobby(local, () -> false, Map.of()));
            Assert.assertTrue(local.webSeat() >= 0, "the browser never took its seat");
            body.run(local, ref.get());
        } finally {
            gui.close();
            onUi(local::close);
        }
    }

    /** The first seat that is not the browser's, which the table gives a computer. */
    static int computer(final LocalGame local) {
        return local.webSeat() == 0 ? 1 : 0;
    }
}
