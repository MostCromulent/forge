package forge.web;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.ArchetypeDeckGenerator;
import forge.deck.DeckFormat;
import forge.deck.DeckProxy;
import forge.deck.DeckUrlLoader;
import forge.deck.DeckgenUtil;
import forge.deck.NetDeckCategory;
import forge.deck.DeckSection;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.quest.QuestController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.MyRandom;
import forge.util.SleeveArt;
import forge.web.ToBrowser.DeckCard;
import forge.web.ToBrowser.DeckDetails;
import forge.web.ToBrowser.DeckGroup;
import forge.web.ToBrowser.DeckStats;
import forge.web.ToBrowser.DeckSummary;
import forge.web.ToBrowser.ExtraChoice;
import forge.web.ToBrowser.Printing;
import forge.web.ToBrowser.SavedSleeveArt;
import forge.web.ToBrowser.TypeCount;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decks a seat may choose from, and what the browser shows about each one: your own, the preconstructed ones,
 * quest opponents, generated decks and downloaded net decks, for each format the lobby offers.
 */
final class DeckCatalog {
    /** Where a deck came from, which the browser tags each row with. */
    static final String MINE = "yours";
    /** A guest's decks, kept in the guest's browser and keyed by the id it keeps them under. */
    static final String DEVICE = "device";
    static final String LINKED = "linked";
    private static final String PRECON = "precons";
    private static final String QUEST = "quest";
    /** Sources that make a deck when you pick one, rather than loading a saved deck. */
    private static final String GENERATED = "generated";
    private static final String NET = "net";
    /** A colour generator knows its own colour before it builds anything; the other generators do not. */
    private static final Map<String, String> COLOUR_LETTERS = Map.of(
            "White", "W", "Blue", "U", "Black", "B", "Red", "R", "Green", "G");
    /** Mana values 0 to 5, then everything 6 and above in the last one. */
    private static final int CURVE_BUCKETS = 7;

    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();

    /** A generator's deck is built on demand and kept, so the panel and the match see the same cards. */
    private record Entry(DeckProxy proxy, boolean generated, Deck built) {
        Entry(final DeckProxy proxy) {
            this(proxy, false, null);
        }
    }

    /** The sanctioned formats a deck can be filtered by, which is a card-pool question and not the game type. */
    static List<String> cardFormats() {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            out.add(f.getName());
        }
        return out;
    }

    /** Which of those formats this deck's cards are all legal in. */
    private static List<String> legalIn(final Deck deck) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            if (f.isDeckLegal(deck)) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** Categories the browser has asked for and core has downloaded, kept so a refresh does not lose them. */
    private final List<NetDeckCategory> netCategories = new ArrayList<>();

    /**
     * Held while anything reads or changes a deck. Every browser's catalogue reads the same decks from Forge's deck
     * storage, and a deck loads its sections the first time it is read, which is not safe from two threads at once:
     * two browsers opening match setup together could each find a deck half loaded. Match setup reads decks too,
     * so it takes the same lock.
     */
    static final Object DECKS = new Object();

    /** Asks core for a net deck category. Core picks one through the browser and downloads it. */
    void loadNetDecks(final GameType format) {
        // Picking waits on the host's answer, so the decks are locked only to record what was picked
        final NetDeckCategory category = NetDeckCategory.selectAndLoad(format);
        synchronized (DECKS) {
            if (category != null && !netCategories.contains(category)) {
                netCategories.add(category);
            }
        }
    }

    /** The card pool the last refresh was for, which generators build from and every verdict checks. */
    private volatile GameFormat pool;

    /** A deck a guest keeps in its browser, and the format it was built for. */
    record OnDevice(Deck deck, GameType format) {
    }

    /** Whether the browser this catalogue serves is a guest's, whose own decks are those on its device. */
    private boolean guest;

    /**
     * Rebuilds the catalogue for a format and card pool, and returns every deck in it. A guest's list adds the decks its
     * browser keeps; the host's adds the decks it has loaded from links.
     */
    List<DeckSummary> refresh(final GameType format, final GameFormat pool, final boolean guest, final Map<String, OnDevice> device) {
        synchronized (DECKS) {
            this.guest = guest;
            // A built generator stays built while the pool stands, so the seat that holds it and the list agree
            final Map<String, Entry> built = new HashMap<>();
            if (pool == this.pool) {
                byKey.forEach((key, e) -> {
                    if (e.built() != null) {
                        built.put(key, e);
                    }
                });
            }
            this.pool = pool;
            byKey.clear();
            final List<DeckSummary> out = new ArrayList<>();
            // Momir Basic and MoJhoSto deal their own decks, so there are none to list
            if (format.isAutoGenerated()) {
                return out;
            }
            // Each commander format keeps its own decks; only Commander has precons of its own
            add(out, format, switch (format) {
                case Commander -> DeckProxy.getAllCommanderDecks();
                case Oathbreaker -> DeckProxy.getAllOathbreakerDecks();
                case Brawl -> DeckProxy.getAllBrawlDecks();
                case TinyLeaders -> DeckProxy.getAllTinyLeadersDecks();
                default -> DeckProxy.getAllConstructedDecks();
            }, MINE);
            if (format == GameType.Commander) {
                add(out, format, DeckProxy.getAllCommanderPreconDecks(), PRECON);
            } else if (format == GameType.Constructed) {
                add(out, format, DeckProxy.getAllPreconstructedDecks(QuestController.getPrecons()), PRECON);
                add(out, format, DeckProxy.getAllQuestEventAndChallenges(), QUEST);
                addGenerators(out);
            }
            for (final NetDeckCategory category : netCategories) {
                add(out, format, DeckProxy.getNetDecks(category), NET + " " + category.getName());
            }
            if (!guest) {
                add(out, format, linkedDecks(format), LINKED);
            }
            device.forEach((id, d) -> {
                if (DeckStore.family(d.format()) == DeckStore.family(format)) {
                    addDevice(out, format, id, d.deck());
                }
            });
            built.forEach((key, e) -> byKey.computeIfPresent(key, (k, fresh) -> e));
            return out;
        }
    }

    Deck deck(final String key) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            if (e == null) {
                return null;
            }
            // A generator has no deck until it is asked for one, and builds a different deck each time
            if (e.built() != null) {
                return e.built();
            }
            final Deck deck = e.proxy() == null ? buildColours(key, pool) : e.proxy().getDeck();
            if (e.generated()) {
                byKey.put(key, new Entry(e.proxy(), true, deck));
            }
            return deck;
        }
    }

    /**
     * Registers a deck under the key the finder gives it, replacing the cached copy. The editor and the importer save a
     * new deck object each time, and a seat must be given that one rather than the one loaded when the list was built.
     */
    String adopt(final String tag, final String path, final Deck deck) {
        final String key = DEVICE.equals(tag) ? DEVICE + ":" + path : tag + ":" + path + "/" + deck.getName();
        synchronized (DECKS) {
            byKey.put(key, new Entry(null, false, deck));
        }
        return key;
    }

    /** Whether the deck behind a key can't be changed in place. A guest may change only the decks in its own browser. */
    static boolean readOnly(final String key, final boolean guest) {
        final String tag = key.substring(0, Math.max(0, key.indexOf(':')));
        return guest ? !DEVICE.equals(tag) : !MINE.equals(tag);
    }

    /** The folder path in a key, "" for a deck at the top of its format's folder. */
    static String pathOf(final String key) {
        final int colon = key.indexOf(':');
        final int slash = key.lastIndexOf('/');
        return colon < 0 || slash <= colon ? "" : key.substring(colon + 1, slash);
    }

    /** Builds the colour generator behind a "gen:color:" key, from the chosen card pool when there is one. */
    private static Deck buildColours(final String key, final GameFormat pool) {
        final List<String> selection = List.of(key.substring(key.lastIndexOf(':') + 1));
        return DeckgenUtil.colorCheck(selection)
                ? DeckgenUtil.buildColorDeck(selection, pool == null ? null : pool.getFilterPrinted(), false) : null;
    }

    /** Writes a card-art sleeve onto a deck and saves it, the way the desktop lobby does. */
    boolean saveSleeveArt(final String key, final String imageKey, final int offset) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            final Deck deck = e == null || e.proxy() == null ? null : e.proxy().getDeck();
            if (deck == null) {
                return false;
            }
            deck.setSleeveArtKey(imageKey);
            deck.setSleeveArtOffset(SleeveArt.clampOffset(offset));
            rememberSleeveArt(imageKey, offset);
            return e.proxy().saveDeck();
        }
    }

    /** The chosen deck's card list, grouped the way a decklist is read, for the panel beside the results. */
    DeckDetails details(final String key, final GameType format) {
        synchronized (DECKS) {
            final Deck deck = deck(key);
            if (deck == null) {
                return null;
            }
            return new DeckDetails(key, deck.getName(), problem(deck, format, pool), colors(deck), stats(deck),
                    groups(deck.get(DeckSection.Main)), cards(deck.get(DeckSection.Sideboard)), deck.getSleeveArtKey(),
                    deck.getSleeveArtOffset());
        }
    }

    /** The deck's colour identity as WUBRG letters, or "C" when it has none. */
    static String colors(final Deck deck) {
        final ColorSet identity = DeckProxy.getColorIdentity(deck);
        final StringBuilder sb = new StringBuilder();
        if (identity.hasWhite()) {
            sb.append('W');
        }
        if (identity.hasBlue()) {
            sb.append('U');
        }
        if (identity.hasBlack()) {
            sb.append('B');
        }
        if (identity.hasRed()) {
            sb.append('R');
        }
        if (identity.hasGreen()) {
            sb.append('G');
        }
        return sb.length() == 0 ? "C" : sb.toString();
    }

    /** What the panel reports about a deck beside its card list. */
    static DeckStats stats(final Deck deck) {
        final CardPool main = deck.get(DeckSection.Main);
        final int[] curve = new int[CURVE_BUCKETS];
        final Map<String, Integer> types = new LinkedHashMap<>();
        int lands = 0;
        int spells = 0;
        int totalMana = 0;
        if (main != null) {
            for (final Map.Entry<PaperCard, Integer> e : main) {
                final PaperCard card = e.getKey();
                final int n = e.getValue();
                types.merge(CardCatalog.heading(card), n, Integer::sum);
                if (card.getRules().getType().isLand()) {
                    lands += n;
                    continue;
                }
                // A land has no mana value worth curving, so the curve and the average are spells only
                final int cmc = card.getRules().getManaCost().getCMC();
                curve[Math.min(cmc, CURVE_BUCKETS - 1)] += n;
                spells += n;
                totalMana += cmc * n;
            }
        }
        final List<Integer> buckets = new ArrayList<>();
        for (final int n : curve) {
            buckets.add(n);
        }
        final List<TypeCount> typeCounts = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : types.entrySet()) {
            typeCounts.add(new TypeCount(e.getKey(), e.getValue()));
        }
        return new DeckStats(count(main), count(deck.get(DeckSection.Sideboard)), lands,
                spells == 0 ? 0 : Math.round((totalMana * 100f) / spells) / 100f, buckets, typeCounts);
    }

    /** Sources that build a deck when you pick one. They are listed by name only: there is nothing to
     *  measure, and asking each for a deck just to fill a row would build hundreds of them. */
    private void addGenerators(final List<DeckSummary> out) {
        // The tokens the colour generator expects, shown under friendlier names
        final Map<String, String> colours = new LinkedHashMap<>();
        colours.put("Random 1", "Random one colour");
        colours.put("Random 2", "Random two colours");
        colours.put("Random 3", "Random three colours");
        for (final String c : List.of("White", "Blue", "Black", "Red", "Green")) {
            colours.put(c, c);
        }
        for (final Map.Entry<String, String> c : colours.entrySet()) {
            final String key = "gen:color:" + c.getKey();
            byKey.put(key, new Entry(null, true, null));
            generated(out, key, c.getValue(), "Built when you pick it", COLOUR_LETTERS.getOrDefault(c.getKey(), ""));
        }
        // A theme deck is a fixed list and cannot be held to a card pool
        if (pool == null) {
            for (final DeckProxy theme : DeckProxy.getAllThemeDecks()) {
                byKey.put("gen:theme:" + theme.getName(), new Entry(theme, true, null));
                generated(out, "gen:theme:" + theme.getName(), theme.getName(), "Theme deck", "");
            }
        }
        if (FModel.isdeckGenMatrixLoaded()) {
            for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
                if (pool != null && f != pool) {
                    continue;
                }
                for (final DeckProxy archetype : ArchetypeDeckGenerator.getMatrixDecks(f, false)) {
                    final String key = "gen:archetype:" + f.getName() + ":" + archetype.getName();
                    byKey.put(key, new Entry(archetype, true, null));
                    generated(out, key, archetype.getName(), f.getName() + " archetype", "");
                }
            }
        }
    }

    // Colours are empty where they are not known until the deck is built, which a colour filter treats as no match
    private static void generated(final List<DeckSummary> out, final String key, final String name, final String note,
            final String colours) {
        out.add(new DeckSummary(key, name, GENERATED, colours, true, note, null, null, null, null, null, null, null, true,
                null, null, null));
    }

    private void add(final List<DeckSummary> out, final GameType format, final Iterable<DeckProxy> source, final String tag) {
        for (final DeckProxy proxy : source) {
            final String key = tag + ":" + proxy.getPath() + "/" + proxy.getName();
            byKey.put(key, new Entry(proxy));
            final Deck deck = proxy.getDeck();
            // An illegal deck is shown and marked rather than hidden, so nobody hunts for a deck that is there.
            // Its formats are the same wording the desktop chooser puts in its format column.
            final boolean linked = LINKED.equals(tag);
            out.add(new DeckSummary(key, proxy.getName(), tag, colors(deck), null, null, count(deck.get(DeckSection.Main)),
                    count(deck.get(DeckSection.Sideboard)), problem(deck, format, pool), legalIn(deck), proxy.getFormatsString(),
                    deck.getSleeveArtKey(), deck.getSleeveArtOffset(), readOnly(key, guest),
                    linked ? site(deck.getSourceUrl()) : null, linked ? deck.getSourceUrl() : null,
                    linked ? linkedFile(deck).lastModified() : null));
        }
    }

    private void addDevice(final List<DeckSummary> out, final GameType format, final String id, final Deck deck) {
        final String key = DEVICE + ":" + id;
        byKey.put(key, new Entry(null, false, deck));
        out.add(new DeckSummary(key, deck.getName(), DEVICE, colors(deck), null, null, count(deck.get(DeckSection.Main)),
                count(deck.get(DeckSection.Sideboard)), problem(deck, format, pool), legalIn(deck), null,
                deck.getSleeveArtKey(), deck.getSleeveArtOffset(), false, null, null, null));
    }

    /** The decks loaded from links that belong to this format: each keeps its format, or is Commander when it has a commander. */
    private static List<DeckProxy> linkedDecks(final GameType format) {
        final List<DeckProxy> out = new ArrayList<>();
        for (final DeckProxy proxy : DeckUrlLoader.getUrlDecks()) {
            final Deck deck = proxy.getDeck();
            final GameType family = deck.getDeckFormat() != null ? familyOf(deck.getDeckFormat())
                    : deck.has(DeckSection.Commander) ? GameType.Commander : GameType.Constructed;
            if (family == DeckStore.family(format)) {
                out.add(proxy);
            }
        }
        return out;
    }

    static GameType familyOf(final DeckFormat deckFormat) {
        return switch (deckFormat) {
            case Commander -> GameType.Commander;
            case Oathbreaker -> GameType.Oathbreaker;
            case Brawl -> GameType.Brawl;
            case TinyLeaders -> GameType.TinyLeaders;
            default -> GameType.Constructed;
        };
    }

    /** The site a linked deck came from, as its makers write its name. */
    static String site(final String url) {
        final String host = url == null ? "" : url.toLowerCase();
        if (host.contains("moxfield.com")) {
            return "Moxfield";
        }
        if (host.contains("archidekt.com")) {
            return "Archidekt";
        }
        if (host.contains("tappedout.net")) {
            return "TappedOut";
        }
        return host.contains("mtggoldfish.com") ? "MTGGoldfish" : "the link";
    }

    // Loading a link again rewrites its file, so the file's time is when the deck was last synced
    private static File linkedFile(final Deck deck) {
        return new File(ForgeConstants.DECK_BASE_DIR + "URL" + ForgeConstants.PATH_SEPARATOR + deck.getBestFileName() + ".dck");
    }

    /** Why this deck cannot be played here, or null when it can. A chosen card pool is checked even with deck legality checks off. */
    static String problem(final Deck deck, final GameType format, final GameFormat pool) {
        if (deck == null) {
            return "No deck chosen.";
        }
        final String outOfPool = pool == null ? null : poolProblem(pool, deck);
        if (outOfPool != null || !FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return outOfPool;
        }
        return format.getDeckFormat().getDeckConformanceProblem(deck);
    }

    /** Most cards a problem sentence names before counting the rest. */
    private static final int NAMED_CARDS = 3;

    /** The pool's verdict on a deck as one sentence. GameFormat answers with a header line and a card per line. */
    static String poolProblem(final GameFormat pool, final Deck deck) {
        final String raw = pool.getDeckConformanceProblem(deck);
        if (raw == null) {
            return null;
        }
        final String[] lines = raw.split("\n");
        // GameFormat lists each printing, so one card in two sets would be named twice
        final Set<String> unique = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                unique.add(lines[i].trim());
            }
        }
        final List<String> names = new ArrayList<>(unique);
        final String listed = listOf(names);
        return lines[0].contains("restricted")
                ? pool.getName() + " allows one copy of " + listed + "."
                : "Not legal in " + pool.getName() + ": " + names.size() + (names.size() == 1 ? " card. " : " cards. ")
                        + listed + ".";
    }

    /** "A", "A and B", "A, B and C", or "A, B, C and 4 more". */
    private static String listOf(final List<String> names) {
        final List<String> shown = names.subList(0, Math.min(NAMED_CARDS, names.size()));
        final int more = names.size() - shown.size();
        if (more > 0) {
            return String.join(", ", shown) + " and " + more + " more";
        }
        if (shown.size() == 1) {
            return shown.get(0);
        }
        return String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
    }

    private static int count(final CardPool pool) {
        return pool == null ? 0 : pool.countAll();
    }

    private static List<DeckCard> cards(final CardPool pool) {
        final List<DeckCard> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            out.add(card(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static DeckCard card(final PaperCard card, final int count) {
        return new DeckCard(card.getName(), count, card.getImageKey(false));
    }

    /** Main-deck cards under the headings a decklist normally carries. */
    private static List<DeckGroup> groups(final CardPool pool) {
        final Map<String, List<DeckCard>> sections = new LinkedHashMap<>();
        for (final String heading : CardCatalog.HEADINGS) {
            sections.put(heading, new ArrayList<>());
        }
        if (pool != null) {
            for (final Map.Entry<PaperCard, Integer> e : pool) {
                sections.get(CardCatalog.heading(e.getKey())).add(card(e.getKey(), e.getValue()));
            }
        }
        final List<DeckGroup> out = new ArrayList<>();
        for (final Map.Entry<String, List<DeckCard>> e : sections.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(new DeckGroup(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** A seat's choice for one extra section: what it is called, and its cards, or null to follow the main deck's own. */
    record Extra(String label, CardPool cards) {
    }

    static final String OWN = "own";
    static final String GENERATE = "generate";
    static final String RANDOM = "random";
    private static final String SAVED = "deck:";
    private static final String AVATAR = "avatar:";

    /** What a seat may choose for a planar deck, a scheme deck or an avatar, most common first. */
    static List<ExtraChoice> extraChoices(final DeckSection section, final boolean forComputer, final Deck main) {
        final List<ExtraChoice> out = new ArrayList<>();
        final CardPool own = main == null ? null : main.get(section);
        if (own != null && !own.isEmpty()) {
            out.add(new ExtraChoice(OWN, section == DeckSection.Avatar ? "The deck's default" : "The deck's own",
                    own.countAll(), sectionProblem(section, own), null, null, null, null));
        }
        if (section == DeckSection.Avatar) {
            out.add(new ExtraChoice(RANDOM, "Random", null, null, null, null, null, null));
            for (final PaperCard avatar : avatars(forComputer)) {
                out.add(new ExtraChoice(AVATAR + avatar.getName(), avatar.getName(), null, null, avatar.getImageKey(false),
                        avatar.getRules().getHand(), avatar.getRules().getLife(),
                        !avatar.getRules().getAiHints().getRemAIDecks()));
            }
            return out;
        }
        out.add(new ExtraChoice(GENERATE, "Generated", null, null, null, null, null, null));
        out.add(new ExtraChoice(RANDOM, "Random saved deck", null, null, null, null, null, null));
        for (final DeckProxy proxy : savedDecks(section)) {
            final CardPool cards = proxy.getDeck().get(section);
            out.add(new ExtraChoice(SAVED + proxy.getPath() + "/" + proxy.getName(), proxy.getName(),
                    cards == null ? 0 : cards.countAll(), sectionProblem(section, cards), null, null, null, null));
        }
        return out;
    }

    /** The cards a choice stands for. Generated and random choices are drawn now, so the seat shows what it got. */
    static Extra resolveExtra(final DeckSection section, final String choice, final boolean forComputer) {
        if (OWN.equals(choice)) {
            return new Extra(section == DeckSection.Avatar ? "The deck's default" : "The deck's own", null);
        }
        if (section == DeckSection.Avatar) {
            final List<PaperCard> pool = avatars(forComputer);
            PaperCard avatar = null;
            if (RANDOM.equals(choice)) {
                // A random avatar skips the ones marked unfit for a random pick, as desktop's does
                final List<PaperCard> fit = pool.stream().filter(c -> !c.getRules().getAiHints().getRemRandomDecks()).toList();
                avatar = fit.isEmpty() ? null : fit.get(MyRandom.getRandom().nextInt(fit.size()));
            } else if (choice != null && choice.startsWith(AVATAR)) {
                final String name = choice.substring(AVATAR.length());
                avatar = pool.stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
            }
            if (avatar == null) {
                return null;
            }
            final CardPool one = new CardPool();
            one.add(avatar);
            return new Extra(RANDOM.equals(choice) ? "Random" : avatar.getName(), one);
        }
        if (RANDOM.equals(choice)) {
            final List<DeckProxy> saved = savedDecks(section);
            if (!saved.isEmpty()) {
                final DeckProxy pick = saved.get(MyRandom.getRandom().nextInt(saved.size()));
                return new Extra("Random saved deck", pick.getDeck().get(section));
            }
        } else if (choice != null && choice.startsWith(SAVED)) {
            final String key = choice.substring(SAVED.length());
            for (final DeckProxy proxy : savedDecks(section)) {
                if ((proxy.getPath() + "/" + proxy.getName()).equals(key)) {
                    return new Extra(proxy.getName(), proxy.getDeck().get(section));
                }
            }
            return null;
        }
        return new Extra("Generated", section == DeckSection.Planes ? DeckgenUtil.generatePlanarPool()
                : DeckgenUtil.generateSchemePool());
    }

    /** Why a planar or scheme deck cannot be played, or null. An avatar section has no size rule. */
    static String sectionProblem(final DeckSection section, final CardPool cards) {
        return switch (section) {
            case Planes -> forge.deck.DeckFormat.getPlaneSectionConformanceProblem(cards);
            case Schemes -> forge.deck.DeckFormat.getSchemeSectionConformanceProblem(cards);
            default -> null;
        };
    }

    private static List<DeckProxy> savedDecks(final DeckSection section) {
        final List<DeckProxy> out = new ArrayList<>();
        synchronized (DECKS) {
            (section == DeckSection.Planes ? DeckProxy.getAllPlanarDecks() : DeckProxy.getAllSchemeDecks()).forEach(out::add);
        }
        return out;
    }

    /** Every Vanguard card, or only those the computer can play well for a computer seat. */
    private static List<PaperCard> avatars(final boolean forComputer) {
        final List<PaperCard> out = new ArrayList<>();
        for (final PaperCard card : FModel.getMagicDb().getVariantCards().getAllCards()) {
            if (card.getRules().getType().isVanguard() && !(forComputer && card.getRules().getAiHints().getRemAIDecks())) {
                out.add(card);
            }
        }
        out.sort(Comparator.comparing(PaperCard::getName));
        return out;
    }

    /** The card-art sleeves already saved, shared with the desktop client. */
    static List<SavedSleeveArt> savedSleeveArt() {
        final List<SavedSleeveArt> out = new ArrayList<>();
        final Map<String, Integer> library = SleeveArt.parseLibrary(
                FModel.getPreferences().getPref(FPref.UI_SLEEVE_ART_LIBRARY));
        for (final Map.Entry<String, Integer> e : library.entrySet()) {
            out.add(new SavedSleeveArt(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Remembers a card-art sleeve alongside the ones desktop has saved, newest last. */
    static void rememberSleeveArt(final String imageKey, final int offset) {
        if (imageKey == null || imageKey.isEmpty()) {
            return;
        }
        final var prefs = FModel.getPreferences();
        final FPref pref = FPref.UI_SLEEVE_ART_LIBRARY;
        final Map<String, Integer> library = new LinkedHashMap<>(SleeveArt.parseLibrary(prefs.getPref(pref)));
        library.remove(imageKey);
        library.put(imageKey, SleeveArt.clampOffset(offset));
        prefs.setPref(pref, SleeveArt.formatLibrary(library));
        prefs.save();
    }

    /**
     * Card names matching what has been typed, for the card-art sleeve picker. Ranked as desktop's ListChooser and the
     * browser's lists rank: names starting with the text first, shortest first, then names containing it.
     */
    static List<String> searchCardNames(final String query, final int limit) {
        final String text = CardCatalog.normalize(query == null ? "" : query);
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        final List<String> startsWith = new ArrayList<>();
        final List<String> contains = new ArrayList<>();
        for (final PaperCard card : StaticData.instance().getCommonCards().getUniqueCards()) {
            final String name = CardCatalog.normalize(card.getName());
            if (name.startsWith(text)) {
                startsWith.add(card.getName());
            } else if (name.contains(text)) {
                contains.add(card.getName());
            }
        }
        startsWith.sort(Comparator.comparingInt(String::length));
        startsWith.addAll(contains);
        return new ArrayList<>(startsWith.subList(0, Math.min(limit, startsWith.size())));
    }

    /** Every printing of one card, so a specific art can be picked for a sleeve. */
    static List<Printing> printings(final String name, final GameFormat pool) {
        final List<Printing> out = new ArrayList<>();
        for (final PaperCard card : StaticData.instance().getCommonCards().getAllCardsNoAlt(name)) {
            final CardEdition edition = StaticData.instance().getEditions().get(card.getEdition());
            final Calendar date = Calendar.getInstance();
            if (edition != null) {
                date.setTime(edition.getDate());
            }
            out.add(new Printing(card.getName(), card.getEdition(), card.getImageKey(false),
                    edition == null ? card.getEdition() : edition.getName(), edition == null ? 0 : date.get(Calendar.YEAR),
                    pool != null && !pool.getFilterPrinted().test(card) ? "not in " + pool.getName() : null));
        }
        return out;
    }
}
