package forge.web.deck;

import forge.game.GameType;
import forge.util.IHasForgeLog;
import forge.web.WebGuiBase;
import forge.web.deck.DeckCatalog.OnDevice;
import forge.web.lobby.Lobby;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.Commands;
import forge.web.protocol.FromBrowser.*;
import org.apache.commons.lang3.EnumUtils;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** One browser's deck editor and importer. It also holds the decks that a guest's browser stores. */
public final class DeckSession implements IHasForgeLog {
    public static final Commands<DeckSession> COMMANDS = new Commands<DeckSession>()
            .on(BrowseFormat.class, (d, ch, b) -> {
                if (d.host.getAsBoolean() && !d.atTable.getAsBoolean()) {
                    d.lobby.setBrowseFormat(gameType(b.format()));
                    d.lobby.queueDecks(ch, d::inTurn);
                }
            })
            // The stars belong to the host and are stored where desktop stores them
            .on(DeckFavourite.class, (d, ch, star) -> {
                if (d.host.getAsBoolean()) {
                    d.lobby.catalog().setFavourite(star.key(), star.on());
                    d.lobby.queueDecks(ch, d::inTurn);
                }
            })
    private final Lobby lobby;
    private final WebGuiBase ui;
    private final BooleanSupplier host;
    private final BooleanSupplier atTable;
    private final Executor work = WebGuiBase.worker("WebDecks");

    public DeckSession(final Lobby lobby, final WebGuiBase ui, final BooleanSupplier host, final BooleanSupplier atTable,
            final Map<String, OnDevice> device) {
        this.lobby = lobby;
        this.ui = ui;
        this.host = host;
        this.atTable = atTable;
        this.device = device;
    }

    public void onCommand(final BrowserChannel channel, final Record command) {
        COMMANDS.run(this, channel, command);
    }

    /**
     * Runs a deck list, search or catalogue request on this browser's deck thread, in the order requested. The socket threads
     * also serve every picture, so a list that takes a second to build would block them.
     */
    public void inTurn(final Runnable job) {
        work.execute(job);
    }

    private static GameType gameType(final String name) {
        return EnumUtils.getEnum(GameType.class, name, GameType.Constructed);
    }

}
