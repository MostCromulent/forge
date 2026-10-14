package forge.web;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.deck.CardPool;
import forge.deck.CommanderOptions;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ImageUtil;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.NameGenerator;
import org.apache.commons.lang3.Range;
import forge.web.FromBrowser.*;
import forge.web.ToBrowser.*;
import forge.web.DeckCatalog.Extra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Match setup read from the engine's lobby: a browser changes its own seat as a client, and the host sets the rest of the table. */
final class Lobby {
    /** A match seats at most four; a draft pod seats up to eight, of whom any four play each match. */
    private static final int MOST_TEAMS = 4;
    static final int MAX_SEATS = 8;
    static final int MAX_POD = 8;
    /** The formats on offer, as desktop orders them. Each but Constructed is a variant; Constructed is the absence of one. */
    private static final List<GameType> FORMATS = List.of(GameType.Constructed, GameType.Commander,
            GameType.Brawl, GameType.Oathbreaker, GameType.TinyLeaders, GameType.MomirBasic, GameType.MoJhoSto);

    /** The casual variants, which stack on top of any format the engine allows them with. */
    static final List<GameType> VARIANTS = List.of(GameType.Vanguard, GameType.Planechase,
            GameType.Archenemy, GameType.ArchenemyRumble);

    private final DeckCatalog catalog = new DeckCatalog();
    /** Each seat's planar deck, scheme deck and avatar, for the seats this browser deals for. */
    private final Map<Integer, Map<DeckSection, Extra>> extras = new ConcurrentHashMap<>();
    /** The deck each of those seats was last sent, main deck and extras together: the deck of record. */
    private final Map<Integer, Deck> composed = new ConcurrentHashMap<>();
    /** The commanders a seat's player chose to lead its deck, by name, until another deck is chosen. */
    private final Map<Integer, List<String>> commanderPicks = new ConcurrentHashMap<>();
    private final LocalGame local;
    private final LobbyEvents events;
    /** Which deck each seat was given, by catalogue key: the lobby slot holds the deck, this holds the choice. */
    private final List<String> deckKeys = new ArrayList<>();
    /** True once the game was opened for others to join, which is when a link is worth showing. */
    private boolean shareable;

    Lobby(final LocalGame local, final BooleanSupplier guest, final Map<String, DeckCatalog.OnDevice> device) {
        this.local = local;
        this.events = new LobbyEvents(this, local);
        this.guest = guest;
        this.device = device;
    }

    /** Whether this browser is a guest's, whose own decks are the ones its browser keeps. */
    private final BooleanSupplier guest;
    /** A guest's decks, by the id its browser keeps each under. */
    private final Map<String, DeckCatalog.OnDevice> device;

    void setShareable(final boolean value) {
        shareable = value;
    }

    /** The server's own lobby for the host, whose client copy trails it by an update, and the client copy for a guest. */
    GameLobby view() {
        final GameLobby hosted = local.hostedLobby();
        return hosted != null ? hosted : local.clientLobby();
    }

    ServerGameLobby host() {
        return local.hostedLobby();
    }

    /** Read from the lobby's variants, because its game type is left out of the serialised data and stays Constructed on a client. */
    GameType format() {
        final GameLobby lobby = view();
        if (lobby == null) {
            return browseFormat;
        }
        // A Limited table plays event decks, which are checked as startGame checks them
        if (limited(lobby)) {
            return GameType.Draft;
        }
        for (final GameType variant : FORMATS) {
            if (variant != GameType.Constructed && lobby.hasVariant(variant)) {
                return variant;
            }
        }
        return GameType.Constructed;
    }

    /** The format the start page's deck finder lists. Only read while no table is open, so it never touches a seat's deck. */
    private volatile GameType browseFormat = GameType.Constructed;

    void setBrowseFormat(final GameType format) {
        browseFormat = format;
    }

    /** The seat this browser sits in, or -1 before it has one. */
    int mySeat() {
        return local.webSeat();
    }

    /** Counts the tables this browser has sat at, so something begun at one table can tell it is now at another. */
    private volatile int table;

    int table() {
        return table;
    }

    /** Registers a saved deck under its finder key, so a seat is given this deck object. See DeckCatalog.adopt. */
    String adopt(final String tag, final String path, final Deck deck) {
        return catalog.adopt(tag, path, deck);
    }

    Deck deck(final String key) {
        return catalog.deck(key);
    }

    void setFavourite(final String key, final boolean on) {
        catalog.setFavourite(key, on);
    }

    /** The description is the web's own wording, since the engine's older lines still call a commander a "General". */
    static Format explained(final GameType type) {
        final Localizer text = Localizer.getInstance();
        // Grouped as Wizards groups formats: Constructed ones, the Commander family, and the rest
        final String group = type == GameType.Constructed ? "Constructed"
                : type.getDeckFormat().hasCommander() ? "Commander" : "Other";
        return new Format(type.name(), type.toString(), group, text.getMessage("lblWebDesc" + type.name()),
                List.of(deckFact(type), lifeFact(type)), text.getMessage("lblWebPlay" + type.name()));
    }

    /** The deck size a player builds to, counting the cards that start in the command zone. */
    private static String deckFact(final GameType type) {
        if (type.isAutoGenerated()) {
            return Localizer.getInstance().getMessage("lblWebFactDeckNone");
        }
        final DeckFormat deck = type.getDeckFormat();
        final Range<Integer> main = deck.getMainRange();
        if (main.getMaximum() == Integer.MAX_VALUE) {
            return Localizer.getInstance().getMessage("lblWebFactDeckAtLeast", main.getMinimum());
        }
        // Oathbreaker's command zone holds the oathbreaker and its signature spell
        final int commandZone = type == GameType.Oathbreaker ? 2 : deck.hasCommander() ? 1 : 0;
        return Localizer.getInstance().getMessage(deck.getMaxCardCopies() == 1 ? "lblWebFactDeckSingleton"
                : "lblWebFactDeckAtLeast", main.getMaximum() + commandZone);
    }

    /** Starting life as RegisteredPlayer.forVariants sets it: 20, plus each format's bonus. */
    private static String lifeFact(final GameType type) {
        final Localizer text = Localizer.getInstance();
        return switch (type) {
            case Commander -> text.getMessage("lblWebFactLife", 40);
            case TinyLeaders -> text.getMessage("lblWebFactLife", 25);
            case MomirBasic, MoJhoSto -> text.getMessage("lblWebFactLifeAvatar");
            case Brawl -> text.getMessage("lblWebFactLifeByPlayers", 25, 30);
            default -> text.getMessage("lblWebFactLife", 20);
        };
    }

    /** The card pool every deck must come from, or null. Only Constructed has one. */
    GameFormat cardPool() {
        final GameLobby lobby = view();
        final String name = lobby == null ? null : lobby.getCardPool();
        return name == null ? null : FModel.getFormats().getFormat(name);
    }

    /** The card pool belongs to the game, so only the host sets it, and only for Constructed. */
    void setCardPool(final String name) {
        final ServerGameLobby lobby = host();
        if (lobby == null || format() != GameType.Constructed) {
            return;
        }
        final boolean known = name != null && FModel.getFormats().getFormat(name) != null;
        lobby.setCardPool(known ? name : null);
    }

    /** The pools on offer, grouped as Forge's format files group them. */
    static CardPools cardPools() {
        final var formats = FModel.getFormats();
        final List<CardPoolGroup> out = new ArrayList<>();
        out.add(new CardPoolGroup("Sanctioned", pools(formats.getSanctionedList())));
        out.add(new CardPoolGroup("Casual", pools(formats.getCasualList())));
        out.add(new CardPoolGroup("Archived", pools(formats.getArchivedList())));
        out.add(new CardPoolGroup("Block", pools(formats.getBlockList())));
        // Which formats load depends on the install, so a heading can come up empty
        out.removeIf(g -> g.formats().isEmpty());
        return new CardPools(out);
    }

    /** Where each format's cards come from, for the card pool picker. */
    static ToBrowser.CardPoolDetails cardPoolDetails() {
        final var formats = FModel.getFormats();
        final List<ToBrowser.CardPoolLine> lines = new ArrayList<>();
        for (final Iterable<GameFormat> list : List.of(formats.getSanctionedList(), formats.getCasualList(), formats.getBlockList())) {
            for (final GameFormat f : list) {
                lines.add(new ToBrowser.CardPoolLine(f.getName(), lineOf(f)));
            }
        }
        return new ToBrowser.CardPoolDetails(lines);
    }

    /** Where a format's cards come from, in a few words. */
    private static String lineOf(final GameFormat f) {
        if (f.getFormatSubType() == GameFormat.FormatSubType.BLOCK) {
            return Localizer.getInstance().getMessage("lblWebLobbySets", f.getAllowedSetCodes().size());
        }
        // Standard rotates, so its first set is not worth naming
        if (f.getFormatSubType() == GameFormat.FormatSubType.STANDARD) {
            return Localizer.getInstance().getMessage("lblWebLobbyRecentSets");
        }
        final List<CardRarity> rarities = f.getAllowedRarities();
        if (rarities != null && !rarities.isEmpty()
                && rarities.stream().noneMatch(r -> r == CardRarity.Uncommon || r == CardRarity.Rare || r == CardRarity.MythicRare)) {
            return Localizer.getInstance().getMessage("lblWebLobbyCommonsOnly");
        }
        CardEdition first = null;
        for (final String code : f.getAllowedSetCodes()) {
            final CardEdition e = StaticData.instance().getEditions().get(code);
            if (e != null && (first == null || e.getDate().before(first.getDate()))) {
                first = e;
            }
        }
        // A list that starts at Alpha is every set, whatever it leaves out after
        if (first == null || "LEA".equals(first.getCode())) {
            return Localizer.getInstance().getMessage(f.getRestrictedCards().isEmpty() ? "lblWebLobbyEverySet" : "lblWebLobbyEverySetRestricted");
        }
        return Localizer.getInstance().getMessage("lblWebLobbySetForward", first.getName());
    }

    /** The formats a 60-card Constructed game can take its cards from. A commander format's file is that format's ban list, so it is left out. */
    private static List<String> pools(final Iterable<GameFormat> formats) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : formats) {
            if (f.getFormatSubType() != GameFormat.FormatSubType.COMMANDER) {
                out.add(f.getName());
            }
        }
        return out;
    }

    private static List<String> names(final Iterable<GameFormat> formats) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : formats) {
            out.add(f.getName());
        }
        return out;
    }

    /** Held while a deck list or the table is built and sent, so two threads cannot both send the same list, or send an older table after a newer one. */
    private final Object sentLock = new Object();
    private BrowserChannel decksSentTo;
    private Decks decksSent;

    /** Skips the send when this browser already has this exact list, but always rebuilds it, as building records what the lobby shows. */
    void sendDecks(final BrowserChannel to) {
        synchronized (sentLock) {
            final Decks out = decks();
            if (to == decksSentTo && out.equals(decksSent)) {
                return;
            }
            decksSentTo = to;
            decksSent = out;
            to.send(out);
        }
    }

    /** Every deck this format can be played with, rebuilt because the pool differs per format. */
    Decks decks() {
        final Decks out;
        final boolean newPool;
        final boolean newRules;
        synchronized (DeckCatalog.DECKS) {
            final GameType format = format();
            final GameFormat cardPool = cardPool();
            final String poolName = cardPool == null ? null : cardPool.getName();
            // A deck key names a deck in one format's lists, so it means nothing in another
            if (seenFormat != null && format != seenFormat) {
                deckKeys.clear();
            }
            newPool = format == seenFormat && !Objects.equals(poolName, seenCardPool);
            newRules = format != seenFormat || !rules().equals(seenRules);
            seenFormat = format;
            seenCardPool = poolName;
            seenRules = rules();
            out = new Decks(catalog.refresh(format, cardPool, guest.getAsBoolean(), device, eventFilter()), names(FModel.getFormats().getSanctionedList()),
                    poolName);
        }
        if (newPool) {
            dealGeneratorsAgain();
        }
        settleReady();
        if (newRules) {
            composeMine();
        }
        return out;
    }

    /** A lone seat is ready once it has a deck, or at once where the format deals the deck, and with another player seated each presses Ready. */
    private void settleReady() {
        final GameLobby lobby = view();
        final int mine = local.webSeat();
        // An event's seats press Ready before any deck exists
        if (lobby == null || mine < 0 || mine >= lobby.getNumberOfSlots() || limited(lobby)) {
            return;
        }
        final boolean others = withOthers(lobby);
        final boolean arrived = others != othersSeated;
        othersSeated = others;
        final boolean ready = !others && (format().isAutoGenerated() || deckAt(mine) != null);
        if ((arrived || ready) && lobby.getSlot(mine).isReady() != ready) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.isReadyUpdate(ready));
        }
    }

    /** Whether a player at another browser holds a seat at this constructed table. An event has its own Ready. */
    private boolean withOthers(final GameLobby lobby) {
        if (limited(lobby)) {
            return false;
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlotType type = lobby.getSlot(i).getType();
            if (i != local.webSeat() && type != LobbySlotType.AI && type != LobbySlotType.OPEN) {
                return true;
            }
        }
        return false;
    }

    /** A slot keeps the generated deck built from the old card pool, so a new pool deals this browser's seats the same generator again. */
    private void dealGeneratorsAgain() {
        final GameLobby lobby = view();
        for (int i = 0; lobby != null && i < deckKeys.size() && i < lobby.getNumberOfSlots(); i++) {
            final String key = deckKeys.get(i);
            final boolean mine = i == local.webSeat() || (host() != null && lobby.getSlot(i).getType() == LobbySlotType.AI);
            if (key != null && key.startsWith("gen:") && mine) {
                setDeck(i, key);
            }
        }
    }

    /** The event whose decks alone the finder lists, or null for every event's; only a Limited table has one. */
    private String eventFilter() {
        final GameLobby lobby = view();
        final GameLobbyData data = lobby == null ? null : lobby.getData();
        return data != null && data.isLimitedMode() && data.isActiveConformance() ? data.getActiveEventId() : null;
    }

    /** The casual variants on, which seat is the archenemy, and the event filter: what decides the decks a seat may take. */
    private String rules() {
        final GameLobby lobby = view();
        if (lobby == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(variantsOn(lobby).toString()).append(eventFilter());
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            sb.append(lobby.getSlot(i).isArchenemy() ? i : "");
        }
        return sb.toString();
    }

    private static List<String> variantsOn(final GameLobby lobby) {
        final List<String> out = new ArrayList<>();
        for (final GameType v : VARIANTS) {
            if (lobby.hasVariant(v)) {
                out.add(v.name());
            }
        }
        return out;
    }

    /** Seats whose deck this browser sends: its own, and the computer's when it runs the game. */
    private boolean dealsFor(final GameLobby lobby, final int index) {
        return index == local.webSeat() || (host() != null && index < lobby.getNumberOfSlots()
                && lobby.getSlot(index).getType() == LobbySlotType.AI);
    }

    private static boolean forComputer(final GameLobby lobby, final int index) {
        return lobby.getSlot(index).getType() == LobbySlotType.AI;
    }

    /** The extra sections a seat brings under the variants that are on. */
    private static List<DeckSection> sectionsFor(final GameLobby lobby, final int index) {
        final List<DeckSection> out = new ArrayList<>();
        if (lobby.hasVariant(GameType.Planechase)) {
            out.add(DeckSection.Planes);
        }
        if (lobby.hasVariant(GameType.ArchenemyRumble)
                || (lobby.hasVariant(GameType.Archenemy) && lobby.getSlot(index).isArchenemy())) {
            out.add(DeckSection.Schemes);
        }
        if (lobby.hasVariant(GameType.Vanguard)) {
            out.add(DeckSection.Avatar);
        }
        return out;
    }

    private Map<DeckSection, Extra> extrasAt(final int index) {
        return extras.computeIfAbsent(index, i -> Collections.synchronizedMap(new EnumMap<>(DeckSection.class)));
    }

    /** Sends the deck of every seat this browser deals for. */
    private void composeMine() {
        final GameLobby lobby = view();
        for (int i = 0; lobby != null && i < lobby.getNumberOfSlots(); i++) {
            if (dealsFor(lobby, i)) {
                compose(i);
            }
        }
    }

    /** A seat lacking a section its variants call for gets generated planes or schemes, or a random avatar. */
    private void fillDefaults(final GameLobby lobby, final int index) {
        for (final DeckSection section : sectionsFor(lobby, index)) {
            if (!extrasAt(index).containsKey(section)) {
                final Extra fallback = DeckCatalog.resolveExtra(section,
                        section == DeckSection.Avatar ? DeckCatalog.RANDOM : DeckCatalog.GENERATE, forComputer(lobby, index));
                if (fallback != null) {
                    extrasAt(index).put(section, fallback);
                }
            }
        }
    }

    /** Sends a seat's main deck and extras in one update, because a full deck update replaces every section. */
    private void compose(final int index) {
        final GameLobby lobby = view();
        if (lobby == null || index >= lobby.getNumberOfSlots() || !dealsFor(lobby, index)) {
            return;
        }
        fillDefaults(lobby, index);
        final Deck main = catalog.deck(key(index));
        final boolean dealt = format().isAutoGenerated();
        Deck deck = null;
        if (main != null || dealt) {
            final List<PaperCard> leaders = main == null ? null : pickedCommanders(index, main);
            deck = main == null ? new Deck(Localizer.getInstance().getMessage("lblWebLobbyDealtDeck"))
                    : leaders == null ? new Deck(main) : CommanderOptions.withCommanders(main, leaders);
            for (final DeckSection section : sectionsFor(lobby, index)) {
                final Extra extra = extrasAt(index).get(section);
                final CardPool cards = extra == null ? null : extra.cards() != null ? extra.cards()
                        : main == null ? null : main.get(section);
                if (cards != null) {
                    deck.putSection(section, cards);
                }
            }
        }
        if (deck == null) {
            composed.remove(index);
        } else {
            composed.put(index, deck);
        }
        final String planes = labelOf(index, DeckSection.Planes);
        final String schemes = labelOf(index, DeckSection.Schemes);
        final String avatar = labelOf(index, DeckSection.Avatar);
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.deckUpdate(deck));
            // Alone, a deck readies the seat; with others seated it waits on Ready. Sent only on a change, as the chat hears each
            final boolean ready = deck != null && !withOthers(lobby);
            if (lobby.getSlot(index).isReady() != ready) {
                local.updateOwnSeat(UpdateLobbyPlayerEvent.isReadyUpdate(ready));
            }
            local.updateOwnSeat(UpdateLobbyPlayerEvent.setDeckSchemePlaneVanguard(
                    deck == null ? null : deck.getName(), schemes, planes, avatar));
        } else {
            final LobbySlot slot = lobby.getSlot(index);
            slot.setDeck(deck);
            slot.setPlanarDeckName(planes);
            slot.setSchemeDeckName(schemes);
            slot.setAvatarVanguard(avatar);
            local.pushLobby();
        }
    }

    private String labelOf(final int index, final DeckSection section) {
        // Read without creating an entry: this runs while the table is drawn, on whichever thread draws it
        final Extra extra = extras.getOrDefault(index, Map.of()).get(section);
        return extra == null ? null : extra.label();
    }

    /** A seat's planar deck, scheme deck or avatar, chosen by a key from {@link #extraChoices}. */
    void setSeatExtra(final int index, final String sectionName, final String choice) {
        final GameLobby lobby = view();
        final DeckSection section = extraSection(sectionName);
        if (lobby == null || section == null || !dealsFor(lobby, index)) {
            return;
        }
        final Extra extra = DeckCatalog.resolveExtra(section, choice, forComputer(lobby, index));
        if (extra != null) {
            extrasAt(index).put(section, extra);
            compose(index);
        }
    }

    ToBrowser.ExtraChoices extraChoices(final int index, final String sectionName) {
        final GameLobby lobby = view();
        final DeckSection section = extraSection(sectionName);
        if (lobby == null || section == null || index < 0 || index >= lobby.getNumberOfSlots()) {
            return null;
        }
        return new ToBrowser.ExtraChoices(section.name(), index,
                DeckCatalog.extraChoices(section, forComputer(lobby, index), catalog.deck(key(index))));
    }

    private static DeckSection extraSection(final String name) {
        for (final DeckSection s : List.of(DeckSection.Planes, DeckSection.Schemes, DeckSection.Avatar)) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** A casual variant belongs to the game, so only the host switches one; the engine drops what it excludes. */
    void setVariant(final String id, final boolean on) {
        final ServerGameLobby lobby = host();
        if (lobby == null) {
            return;
        }
        for (final GameType v : VARIANTS) {
            if (v.name().equals(id)) {
                if (on) {
                    lobby.applyVariant(v);
                } else {
                    lobby.removeVariant(v);
                }
                // The archenemy's role set the teams, so without it every seat goes back to a team of its own
                if (v == GameType.Archenemy && !on) {
                    for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                        lobby.getSlot(i).setTeam(i);
                    }
                }
                local.pushLobby();
                return;
            }
        }
    }

    /** Whether seats can be put on teams: not where a draft's pairings or the archenemy's role already decide the sides. */
    private static boolean teamsAllowed(final GameLobby lobby) {
        return !limited(lobby) && !lobby.hasVariant(GameType.Archenemy) && !lobby.hasVariant(GameType.ArchenemyRumble);
    }

    /** How many teams the table is split into, or 0 for a free-for-all, where every seat is a team of its own. */
    private static int teams(final GameLobby lobby) {
        if (!teamsAllowed(lobby)) {
            return 0;
        }
        final Set<Integer> seen = new HashSet<>();
        boolean shared = false;
        int most = 0;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final int team = lobby.getSlot(i).getTeam();
            shared |= !seen.add(team);
            most = Math.max(most, team);
        }
        return shared ? Math.max(2, most + 1) : 0;
    }

    /** The team counts the host may deal the seats into: fewer teams than seats, since as many is a free-for-all. */
    private static List<Integer> teamChoices(final GameLobby lobby) {
        final List<Integer> out = new ArrayList<>();
        for (int n = 2; teamsAllowed(lobby) && n < lobby.getNumberOfSlots() && n <= MOST_TEAMS; n++) {
            out.add(n);
        }
        return out;
    }

    /** Deals the seats into that many teams in turn, or with 0 puts every seat back on a team of its own. */
    void setTeams(final int count) {
        final ServerGameLobby lobby = host();
        if (lobby == null || (count != 0 && !teamChoices(lobby).contains(count))) {
            return;
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            lobby.getSlot(i).setTeam(count == 0 ? i : i % count);
        }
        local.pushLobby();
    }

    /** Moves a seat to another of the table's teams: your own seat, or any seat if you run the game. */
    void setTeam(final int index, final int team) {
        final GameLobby table = view();
        if (table == null || index < 0 || index >= table.getNumberOfSlots() || team < 0 || team >= teams(table)) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.teamUpdate(team));
        } else if (host() != null) {
            host().getSlot(index).setTeam(team);
            local.pushLobby();
        }
    }

    /** The team a seat added now joins: the smallest of the table's teams, or in a free-for-all one nobody is on. */
    private static int teamForNewSeat(final GameLobby lobby, final int teams) {
        final Map<Integer, Integer> sizes = new HashMap<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            sizes.merge(lobby.getSlot(i).getTeam(), 1, Integer::sum);
        }
        int best = 0;
        for (int team = 0; team < (teams > 0 ? teams : lobby.getNumberOfSlots() + 1); team++) {
            if (teams == 0 && !sizes.containsKey(team)) {
                return team;
            }
            if (sizes.getOrDefault(team, 0) < sizes.getOrDefault(best, 0)) {
                best = team;
            }
        }
        return best;
    }

    /** Moves the archenemy's role. The engine's lobby keeps one archenemy and sets the teams from it. */
    void setArchenemy(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby == null || !lobby.hasVariant(GameType.Archenemy) || index < 0 || index >= lobby.getNumberOfSlots()) {
            return;
        }
        lobby.applyToSlot(index, UpdateLobbyPlayerEvent.create(null, null, -1, -1, -1, true,
                lobby.getSlot(index).isDevMode(), null, null));
        local.pushLobby();
    }

    /** A casual variant with what the lobby says about it, in the same shape as a format. */
    static Format explainedVariant(final GameType type) {
        final Localizer text = Localizer.getInstance();
        final List<String> facts = switch (type) {
            case Planechase -> List.of(text.getMessage("lblWebFactPlanes"));
            case Vanguard -> List.of(text.getMessage("lblWebFactOneAvatar"), text.getMessage("lblWebFactAvatarChanges"));
            case Archenemy -> List.of(text.getMessage("lblWebFactSchemesArchenemy"), text.getMessage("lblWebFactArchenemyLife"));
            default -> List.of(text.getMessage("lblWebFactSchemesEveryone"), text.getMessage("lblWebFactEveryoneLife"));
        };
        return new Format(type.name(), type.toString(), "Casual variants", text.getMessage("lblWebDesc" + type.name()), facts,
                text.getMessage("lblWebPlay" + type.name()));
    }

    /** Downloads a net deck category and adds it to the catalogue. Core asks which one through the browser. */
    void loadNetDecks(final BrowserChannel to) {
        catalog.loadNetDecks(format());
        sendDecks(to);
    }

    ToBrowser.DeckMatches deckMatches(final String kind, final String value) {
        return new ToBrowser.DeckMatches(kind, value, catalog.matching(kind, value));
    }

    DeckDetailsMessage deckDetails(final String key) {
        final DeckDetails details = catalog.details(key, format());
        return details == null ? null : new DeckDetailsMessage(details);
    }

    void sendState(final BrowserChannel to) {
        synchronized (sentLock) {
            to.send(state());
        }
    }

    private LobbyMessage state() {
        synchronized (DeckCatalog.DECKS) {
            final GameLobby lobby = view();
            if (lobby == null) {
                return new LobbyMessage(null);
            }
            final List<Format> formats = FORMATS.stream().map(Lobby::explained).toList();
            final List<Seat> seats = new ArrayList<>();
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                seats.add(seat(lobby, i));
            }
            final List<String> illegal = new ArrayList<>();
            final List<String> problems = problems(illegal);
            final GameFormat cardPool = cardPool();
            // Only the machine running the game can start it; everyone else waits on the host
            return new LobbyMessage(new LobbyTable(local.isHost(), local.webSeat(), shareable, format().name(), formats,
                    cardPool == null ? null : cardPool.getName(),
                    VARIANTS.stream().map(Lobby::explainedVariant).toList(), variantsOn(lobby),
                    maxSeats(), FModel.getPreferences().getPrefInt(FPref.UI_MATCHES_PER_GAME), seats, problems,
                    local.isHost() && problems.isEmpty(), illegal, FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY),
                    events.limitedTable(lobby), maxBracket(), overBracket(lobby), teams(lobby),
                    local.isHost() ? teamChoices(lobby) : List.of()));
        }
    }

    private Seat seat(final GameLobby lobby, final int index) {
        final LobbySlot slot = lobby.getSlot(index);
        final Deck deck = deckAt(index);
        // Your own seat wherever you are, and the computer's seats if you run the game. Another player's is theirs.
        final boolean mayEdit = index == local.webSeat() || (local.isHost() && slot.getType() == LobbySlotType.AI);
        return new Seat(slot.getName(), slot.getType().name(), index == local.webSeat(), mayEdit,
                slot.isReady(), slot.getAvatarIndex(), slot.getSleeveIndex(), key(index),
                deck == null ? null : deck.getName(),
                deck == null ? 0 : DeckCatalog.played(deck),
                deck == null ? "" : DeckCatalog.colors(deck),
                deck == null || format().isAutoGenerated() ? null : DeckCatalog.problem(deck, format(), cardPool()),
                // A deck with a card-art sleeve overrides the numbered one, as it does in every other client
                deck == null ? "" : deck.getSleeveArtKey(),
                deck == null ? 0 : deck.getSleeveArtOffset(),
                lobby.hasVariant(GameType.ArchenemyRumble) ? "archenemy"
                        : lobby.hasVariant(GameType.Archenemy) ? (slot.isArchenemy() ? "archenemy" : "hero") : null,
                sectionsFor(lobby, index).contains(DeckSection.Planes) ? seatExtra(lobby, index, deck, DeckSection.Planes) : null,
                sectionsFor(lobby, index).contains(DeckSection.Schemes) ? seatExtra(lobby, index, deck, DeckSection.Schemes) : null,
                sectionsFor(lobby, index).contains(DeckSection.Avatar) ? seatExtra(lobby, index, deck, DeckSection.Avatar) : null,
                slot.isSittingOut(),
                deck == null || !format().getDeckFormat().hasCommander() || deck.getCommanders().isEmpty() ? null
                        : deck.getCommanders().get(0).getImageKey(false),
                deck == null || format() != GameType.Commander ? null : DeckCatalog.bracketOf(deck),
                teams(lobby) > 0 ? slot.getTeam() : null, commanderChoices(index, mayEdit, deck));
    }

    /** The highest Commander bracket this table plays at, 1 to 4, or 5 for any: the host's own setting, as desktop keeps it. */
    static int maxBracket() {
        return FModel.getPreferences().getPrefInt(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET);
    }

    /** The decks above this table's bracket, which do not stop the match: the host is asked before it starts. */
    private List<String> overBracket(final GameLobby lobby) {
        final List<String> out = new ArrayList<>();
        final int most = maxBracket();
        if (format() != GameType.Commander || most >= 5) {
            return out;
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final Deck deck = deckAt(i);
            if (lobby.getSlot(i).isSittingOut() || deck == null || DeckCatalog.bracketOf(deck) <= most) {
                continue;
            }
            final String why = String.join(", ", DeckCatalog.bracket(deck).reasons().stream()
                    .filter(r -> r.raises() > most).map(ToBrowser.BracketReason::brief).toList());
            out.add(Localizer.getInstance().getMessage("lblWebLobbyAboveBracket", String.valueOf(lobby.getSlot(i).getName()), most, why));
        }
        return out;
    }

    /** Sets the host's bracket preference, which netplay sends to every client with the lobby, and returns whether it changed. */
    boolean setMaxBracket(final int bracket) {
        final var prefs = FModel.getPreferences();
        if (host() == null || bracket < 1 || bracket > 5 || prefs.getPrefInt(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET) == bracket) {
            return false;
        }
        prefs.setPref(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET, String.valueOf(bracket));
        prefs.save();
        local.pushLobby();
        return true;
    }

    /** Another browser's seat is described by the names it published, with a random avatar named "Random" to keep the draw a surprise. */
    private SeatExtra seatExtra(final GameLobby lobby, final int index, final Deck deck, final DeckSection section) {
        final LobbySlot slot = lobby.getSlot(index);
        final String published = switch (section) {
            case Planes -> slot.getPlanarDeckName();
            case Schemes -> slot.getSchemeDeckName();
            default -> slot.getAvatarVanguard();
        };
        final String label = dealsFor(lobby, index) && labelOf(index, section) != null ? labelOf(index, section) : published;
        final CardPool cards = deck == null ? null : deck.get(section);
        final int count = cards == null ? 0 : cards.countAll();
        String detail = null;
        if (section == DeckSection.Avatar && count > 0 && !DeckCatalog.randomLabel().equals(label)) {
            final var rules = cards.iterator().next().getKey().getRules();
            detail = Localizer.getInstance().getMessage("lblWebLobbyAvatarMods", signed(rules.getHand()), signed(rules.getLife()));
        }
        // Before a seat has a deck nothing has been dealt to it, so an empty section is not yet a fault
        final String problem = deck == null ? null : section == DeckSection.Avatar ? (count == 0 ? Localizer.getInstance().getMessage("lblWebLobbyNoAvatar") : null)
                : DeckCatalog.sectionProblem(section, cards);
        return new SeatExtra(label == null ? Localizer.getInstance().getMessage("lblNone") : label, count, detail, problem);
    }

    private static String signed(final int n) {
        return n < 0 ? "−" + (-n) : "+" + n;
    }

    private String key(final int index) {
        return index >= 0 && index < deckKeys.size() ? deckKeys.get(index) : null;
    }

    /** A seat this browser chose for is found by its key, while another player's deck arrives with their seat. */
    private Deck deckAt(final int index) {
        final Deck made = composed.get(index);
        if (made != null) {
            return made;
        }
        final Deck chosen = catalog.deck(key(index));
        if (chosen != null) {
            return chosen;
        }
        final GameLobby lobby = view();
        return lobby != null && index < lobby.getNumberOfSlots() ? lobby.getSlot(index).getDeck() : null;
    }

    /** What stops the match starting, in the order the seats appear. */
    List<String> problems() {
        return problems(new ArrayList<>());
    }

    /** Adds to illegal the decks that break their format's rules, which do not stop the match: the host is asked whether to play anyway. */
    private List<String> problems(final List<String> illegal) {
        synchronized (DeckCatalog.DECKS) {
            final List<String> out = new ArrayList<>();
            final GameLobby lobby = view();
            if (lobby == null) {
                return out;
            }
            int playing = 0;
            final Set<Integer> sides = new HashSet<>();
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                final LobbySlot slot = lobby.getSlot(i);
                // A seat that sits out is not in the match, so nothing about it stops the match
                if (slot.isSittingOut()) {
                    continue;
                }
                if (slot.getType() == LobbySlotType.OPEN) {
                    out.add(Localizer.getInstance().getMessage("lblWebLobbySeatOpen"));
                    continue;
                }
                playing++;
                sides.add(slot.getTeam());
                final boolean you = i == local.webSeat();
                final Deck deck = deckAt(i);
                // Momir Basic and MoJhoSto deal every seat its deck at the start, as startGame does
                if (!format().isAutoGenerated()) {
                    if (deck == null) {
                        out.add(you ? Localizer.getInstance().getMessage("lblWebLobbyYouNoDeck") : Localizer.getInstance().getMessage("lblWebLobbyPlayerNoDeck", String.valueOf(slot.getName())));
                        continue;
                    }
                    final String problem = DeckCatalog.problem(deck, format(), cardPool());
                    if (problem != null) {
                        illegal.add(Localizer.getInstance().getMessage("lblWebLobbyDeckProblem", deck.getName(), problem));
                    }
                }
                // As startGame: a missing avatar always stops the match, the other sections only with legality on
                if (lobby.hasVariant(GameType.Vanguard)
                        && (deck == null || deck.get(DeckSection.Avatar) == null || deck.get(DeckSection.Avatar).isEmpty())) {
                    out.add(you ? Localizer.getInstance().getMessage("lblWebLobbyYouNoAvatar") : Localizer.getInstance().getMessage("lblWebLobbyPlayerNoAvatar", String.valueOf(slot.getName())));
                }
                if (FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
                    for (final DeckSection section : sectionsFor(lobby, i)) {
                        final String fault = section == DeckSection.Avatar ? null
                                : DeckCatalog.sectionProblem(section, deck == null ? null : deck.get(section));
                        if (fault != null) {
                            illegal.add(Localizer.getInstance().getMessage(section == DeckSection.Planes ? "lblWebLobbyPlanarDeckFault" : "lblWebLobbySchemeDeckFault", String.valueOf(slot.getName()), fault));
                        }
                    }
                }
                if (!slot.isReady()) {
                    out.add(Localizer.getInstance().getMessage("lblWebLobbyNotReady", String.valueOf(slot.getName())));
                }
            }
            // As startGame refuses
            if (playing < 2) {
                out.add(0, Localizer.getInstance().getMessage("lblRequiredLeastTwoPlayerStartGame"));
            } else if (sides.size() < 2 && teamsAllowed(lobby)) {
                out.add(0, Localizer.getInstance().getMessage("lblNotEnoughTeams"));
            }
            if (playing > MAX_SEATS) {
                out.add(0, Localizer.getInstance().getMessage("lblWebLobbyTooManyPlayers", MAX_SEATS, playing - MAX_SEATS));
            }
            return out;
        }
    }

    /** Drops the deck choices, because a new lobby's slots hold none and the two must not disagree. */
    void forget() {
        table++;
        deckKeys.clear();
        extras.clear();
        composed.clear();
        commanderPicks.clear();
        seenFormat = null;
        seenCardPool = null;
        seenRules = null;
        othersSeated = false;
    }

    /** The format and card pool this browser's deck list was last built for, recorded by {@link #decks()}. */
    private GameType seenFormat;
    private String seenCardPool;
    private String seenRules;
    /** Whether another player had a seat when the deck list was last built, so their arrival is seen once. */
    private boolean othersSeated;

    /** True when the format or card pool differs from the one the last deck list was built for. */
    boolean restrictionsChanged() {
        synchronized (DeckCatalog.DECKS) {
            final GameFormat cardPool = cardPool();
            return format() != seenFormat || !Objects.equals(cardPool == null ? null : cardPool.getName(), seenCardPool)
                    || !rules().equals(seenRules);
        }
    }

    /** The format belongs to the game, so only the host sets it. */
    void setFormat(final String id) {
        final ServerGameLobby lobby = host();
        if (lobby == null) {
            return;
        }
        for (final GameType wanted : FORMATS) {
            if (!wanted.name().equals(id) || wanted == format()) {
                continue;
            }
            // Cleared first, so no update ever announces another format still holding a Constructed card pool
            if (wanted != GameType.Constructed) {
                lobby.setCardPool(null);
            }
            // Decks are cleared before the variants change, since that change is announced at once and seats are dealt their new decks
            deckKeys.clear();
            composed.clear();
            commanderPicks.clear();
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                lobby.getSlot(i).setDeck(null);
            }
            // Constructed is the absence of a format variant rather than one of its own
            for (final GameType other : FORMATS) {
                if (other != GameType.Constructed) {
                    lobby.removeVariant(other);
                }
            }
            if (wanted != GameType.Constructed) {
                lobby.applyVariant(wanted);
            }
            local.pushLobby();
            return;
        }
    }

    /** The most seats the table takes: a pod's worth at a Limited table, fewer once a configured draft caps its pod. */
    int maxSeats() {
        final GameLobby lobby = view();
        if (lobby == null) {
            return MAX_SEATS;
        }
        return limited(lobby) ? Math.min(MAX_POD, lobby.getSlotLimit()) : MAX_SEATS;
    }

    static boolean limited(final GameLobby lobby) {
        final GameLobbyData data = lobby.getData();
        return data != null && data.isLimitedMode();
    }

    /** Seats stay as they are while their players draft, since each player's seat is where their packs go. */
    static boolean drafting(final ServerGameLobby lobby) {
        return lobby.getDraftHost() != null && !lobby.getDraftHost().isFinished();
    }

    void setEventDecksOnly(final boolean on) {
        events.setEventDecksOnly(on);
    }

    String playEvent(final String eventId, final GameType type, final boolean decksOnly) {
        return events.playEvent(eventId, type, decksOnly);
    }

    String hostAgain(final String eventId) {
        return events.hostAgain(eventId);
    }

    String newEvent() {
        return events.newEvent();
    }

    String forgetEvent(final String eventId) {
        return events.forgetEvent(eventId);
    }

    String setLimited(final String kind) {
        return events.setLimited(kind);
    }

    String setUpEvent(final EventSetup s) {
        return events.setUpEvent(s);
    }

    String startEvent() {
        return events.startEvent();
    }

    boolean settingUpEvent() {
        return events.settingUpEvent();
    }

    void sitOut(final int index, final boolean sittingOut) {
        events.sitOut(index, sittingOut);
    }

    /** Returns whether the host's match length preference changed, since no lobby update says so. */
    boolean setMatchLength(final int games) {
        final var prefs = FModel.getPreferences();
        if (host() == null || (games != 1 && games != 3 && games != 5) || prefs.getPrefInt(FPref.UI_MATCHES_PER_GAME) == games) {
            return false;
        }
        prefs.setPref(FPref.UI_MATCHES_PER_GAME, String.valueOf(games));
        prefs.save();
        return true;
    }

    /** New seats are added at the end, open where others can join and the AI's otherwise. Removal takes open seats first, then the AI's, from the end, never a person's. */
    void setPlayerCount(final int count, final boolean open) {
        final ServerGameLobby lobby = host();
        if (lobby == null || drafting(lobby)) {
            return;
        }
        final int wanted = Math.min(maxSeats(), Math.max(2, count));
        while (lobby.getNumberOfSlots() < wanted) {
            final int before = lobby.getNumberOfSlots();
            addSeat(open);
            if (lobby.getNumberOfSlots() == before) {
                return;
            }
        }
        while (lobby.getNumberOfSlots() > wanted) {
            final int seat = lastSeatOf(lobby, LobbySlotType.OPEN) >= 0 ? lastSeatOf(lobby, LobbySlotType.OPEN) : lastSeatOf(lobby, LobbySlotType.AI);
            if (seat < 0) {
                return;
            }
            removeSeat(seat);
        }
        local.pushLobby();
    }

    private int lastSeatOf(final GameLobby lobby, final LobbySlotType type) {
        for (int i = lobby.getNumberOfSlots() - 1; i >= 0; i--) {
            if (i != local.webSeat() && lobby.getSlot(i).getType() == type) {
                return i;
            }
        }
        return -1;
    }

    private void addSeat(final boolean open) {
        final ServerGameLobby lobby = host();
        if (lobby != null && !drafting(lobby) && lobby.getNumberOfSlots() < maxSeats()) {
            // The engine puts a new seat on the team its index names, which another seat may hold by now
            final int team = teamForNewSeat(lobby, teams(lobby));
            lobby.addSlot();
            lobby.getSlot(lobby.getNumberOfSlots() - 1).setTeam(team);
            if (open) {
                openSeat(lobby.getNumberOfSlots() - 1);
            } else {
                aiSeat(lobby.getNumberOfSlots() - 1);
            }
        }
    }

    /** Leaves a seat for someone to join, rather than filling it with an AI. */
    void openSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && !drafting(lobby) && index != local.webSeat() && index < lobby.getNumberOfSlots()) {
            final LobbySlot slot = lobby.getSlot(index);
            slot.setType(LobbySlotType.OPEN);
            slot.setName(null);
            slot.setIsReady(false);
            slot.setDeck(null);
            // Whoever sits here brings their own choices, so this browser's for the seat are dropped
            if (index < deckKeys.size()) {
                deckKeys.set(index, null);
            }
            extras.remove(index);
            composed.remove(index);
            commanderPicks.remove(index);
            local.pushLobby();
        }
    }

    void aiSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && !drafting(lobby) && index != local.webSeat() && index < lobby.getNumberOfSlots()) {
            final LobbySlot slot = lobby.getSlot(index);
            slot.setType(LobbySlotType.AI);
            slot.setName(computerName(lobby));
            slot.setIsReady(true);
            local.pushLobby();
            compose(index);
        }
    }

    /** A name for the computer as desktop gives one: random, and not one already at the table. */
    static String computerName(final GameLobby lobby) {
        return NameGenerator.getRandomName("Any", "Any", seatNames(lobby, -1));
    }

    private static List<String> seatNames(final GameLobby lobby, final int except) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (i != except && lobby.getSlot(i).getName() != null) {
                out.add(lobby.getSlot(i).getName());
            }
        }
        return out;
    }

    void removeSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && !drafting(lobby) && index != local.webSeat() && lobby.getNumberOfSlots() > 2) {
            lobby.removeSlot(index);
            if (index < deckKeys.size()) {
                deckKeys.remove(index);
            }
            shiftDown(extras, index);
            shiftDown(composed, index);
            shiftDown(commanderPicks, index);
        }
    }

    /** Drops a removed seat's entry and moves the seats after it up one, as the lobby's slots move. */
    private static <T> void shiftDown(final Map<Integer, T> bySeat, final int removed) {
        final Map<Integer, T> moved = new HashMap<>();
        bySeat.forEach((i, v) -> {
            if (i != removed) {
                moved.put(i > removed ? i - 1 : i, v);
            }
        });
        bySeat.clear();
        bySeat.putAll(moved);
    }

    /** The seats at this table that people hold, a browser's or an app's. */
    List<LobbySlot> remoteSeats() {
        final List<LobbySlot> out = new ArrayList<>();
        final GameLobby lobby = view();
        if (lobby != null) {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                final LobbySlot slot = lobby.getSlot(i);
                if (slot.getType() == LobbySlotType.REMOTE && slot.getName() != null) {
                    out.add(slot);
                }
            }
        }
        return out;
    }

    /** The names the computer plays under at this table, which a person may not also take. */
    List<String> computerNames() {
        final List<String> out = new ArrayList<>();
        final GameLobby lobby = view();
        if (lobby != null) {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                final LobbySlot slot = lobby.getSlot(i);
                if (slot.getType() == LobbySlotType.AI && slot.getName() != null) {
                    out.add(slot.getName());
                }
            }
        }
        return out;
    }

    /** A blank name gives the computer a new random one, and leaves a person's as it is. */
    void setName(final int index, final String name) {
        if (name == null) {
            return;
        }
        if (index == local.webSeat()) {
            if (!name.isBlank()) {
                local.updateOwnSeat(UpdateLobbyPlayerEvent.nameUpdate(name.trim()));
            }
        } else if (host() != null && index >= 0 && index < host().getNumberOfSlots()
                && host().getSlot(index).getType() == LobbySlotType.AI
                && seatNames(host(), index).stream().noneMatch(name.trim()::equalsIgnoreCase)) {
            host().getSlot(index).setName(name.isBlank() ? computerName(host()) : name.trim());
            local.pushLobby();
        }
    }

    void setDeck(final int index, final String key) {
        final GameLobby lobby = view();
        if (lobby == null || index < 0 || index >= lobby.getNumberOfSlots()) {
            return;
        }
        while (deckKeys.size() <= index) {
            deckKeys.add(null);
        }
        if (!Objects.equals(key(index), key)) {
            commanderPicks.remove(index);
        }
        deckKeys.set(index, key);
        compose(index);
    }

    /** Leads the seat's deck with one of the commanders it offers, for this table only: the saved deck is not changed. */
    void setCommander(final int index, final int option) {
        final GameLobby lobby = view();
        final Deck main = catalog.deck(key(index));
        if (lobby == null || index < 0 || index >= lobby.getNumberOfSlots() || !dealsFor(lobby, index) || main == null) {
            return;
        }
        final List<CommanderOptions.Option> options = CommanderOptions.getOptions(main, format().getDeckFormat());
        if (option < 0 || option >= options.size()) {
            return;
        }
        commanderPicks.put(index, names(options.get(option).getCommanders()));
        compose(index);
    }

    private List<PaperCard> pickedCommanders(final int index, final Deck main) {
        final List<String> picked = commanderPicks.get(index);
        if (picked == null || !format().getDeckFormat().hasCommander()) {
            return null;
        }
        for (final CommanderOptions.Option option : CommanderOptions.getOptions(main, format().getDeckFormat())) {
            if (names(option.getCommanders()).equals(picked)) {
                return option.getCommanders();
            }
        }
        return null;
    }

    private static List<String> names(final List<PaperCard> cards) {
        return cards.stream().map(PaperCard::getName).toList();
    }

    /** The commanders a seat's deck can be led by, offered only when there is a choice and the seat is this browser's to change. */
    private List<CommanderChoice> commanderChoices(final int index, final boolean mayEdit, final Deck deck) {
        final Deck main = catalog.deck(key(index));
        if (!mayEdit || main == null || deck == null || !format().getDeckFormat().hasCommander()) {
            return List.of();
        }
        final List<CommanderOptions.Option> options = CommanderOptions.getOptions(main, format().getDeckFormat());
        if (options.size() < 2) {
            return List.of();
        }
        final List<String> leading = names(deck.getCommanders());
        return options.stream().map(o -> new CommanderChoice(String.join(" + ", names(o.getCommanders())),
                o.getCommanders().get(0).getImageKey(false), o.getKind().name().toLowerCase(), names(o.getCommanders()).equals(leading))).toList();
    }

    /** Returns whether the seat took the avatar, which it does not when another seat holds it. */
    boolean setAvatar(final int index, final int value) {
        final GameLobby table = view();
        if (value < 0 || value >= SkinSprites.avatarCount() || table == null || avatarsHeld(table, index).contains(value)) {
            return false;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.avatarUpdate(value));
        } else if (host() != null) {
            host().getSlot(index).setAvatarIndex(value);
            local.pushLobby();
        }
        return true;
    }

    private static Set<Integer> avatarsHeld(final GameLobby lobby, final int except) {
        final Set<Integer> held = new HashSet<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (i != except) {
                held.add(lobby.getSlot(i).getAvatarIndex());
            }
        }
        return held;
    }

    /** Players are told apart by portrait in a match, so a seat arriving with one an earlier seat holds takes the first free one. */
    static void distinctAvatars(final GameLobby lobby) {
        final Set<Integer> held = avatarsHeld(lobby, -1);
        final Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot slot = lobby.getSlot(i);
            if (!seen.add(slot.getAvatarIndex())) {
                int free = 0;
                while (held.contains(free)) {
                    free++;
                }
                slot.setAvatarIndex(free);
                held.add(free);
                seen.add(free);
            }
        }
    }

    void setSleeve(final int index, final int value) {
        if (value < 0 || value >= SkinSprites.sleeveCount()) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.sleeveUpdate(value));
        } else if (host() != null) {
            host().getSlot(index).setSleeveIndex(value);
            local.pushLobby();
        }
    }

    /** Deals a seat a sleeve at random: one no seat wears yet while there are enough to go round, and never its own. */
    void dealSleeve(final int index) {
        final GameLobby lobby = view();
        final int count = SkinSprites.sleeveCount();
        if (lobby == null || index < 0 || index >= lobby.getNumberOfSlots() || count < 2) {
            return;
        }
        final List<Integer> free = new ArrayList<>();
        final List<Integer> notMine = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            boolean worn = false;
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                worn |= lobby.getSlot(i).getSleeveIndex() == n;
            }
            if (!worn) {
                free.add(n);
            }
            if (n != lobby.getSlot(index).getSleeveIndex()) {
                notMine.add(n);
            }
        }
        final List<Integer> from = free.isEmpty() ? notMine : free;
        setSleeve(index, from.get(MyRandom.getRandom().nextInt(from.size())));
    }

    void setReady(final boolean ready) {
        local.updateOwnSeat(UpdateLobbyPlayerEvent.isReadyUpdate(ready));
    }

    /** Writes a card-art sleeve onto the seat's deck, where every client reads it from. */
    void setSleeveArt(final int index, final String imageKey, final int offset) {
        final GameLobby lobby = view();
        // The deck file and the saved sleeves are the host's, and the key goes into that file as written
        if (lobby == null || guest.getAsBoolean() || !dealsFor(lobby, index) || imageKey == null
                || (!imageKey.isEmpty() && ImageUtil.getPaperCardFromImageKey(imageKey) == null)) {
            return;
        }
        catalog.saveSleeveArt(key(index), imageKey, offset);
        // The slot holds a copy of the deck, so the new sleeve reaches it only with the deck sent again
        compose(index);
    }

    /** Saves the avatars and sleeves the seats chose, which the desktop lobby shares. */
    void saveLooks() {
        final GameLobby lobby = view();
        // Several browsers share one set of preferences, so only the machine running the game writes them
        if (lobby == null || !local.isHost()) {
            return;
        }
        final StringBuilder avatars = new StringBuilder();
        final StringBuilder sleeves = new StringBuilder();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (i > 0) {
                avatars.append(',');
                sleeves.append(',');
            }
            avatars.append(lobby.getSlot(i).getAvatarIndex());
            sleeves.append(lobby.getSlot(i).getSleeveIndex());
        }
        FModel.getPreferences().setPref(FPref.UI_AVATARS, avatars.toString());
        FModel.getPreferences().setPref(FPref.UI_SLEEVES, sleeves.toString());
        final int mine = local.webSeat();
        if (mine >= 0 && lobby.getSlot(mine).getName() != null) {
            FModel.getPreferences().setPref(FPref.PLAYER_NAME, lobby.getSlot(mine).getName());
        }
        FModel.getPreferences().save();
    }
}
