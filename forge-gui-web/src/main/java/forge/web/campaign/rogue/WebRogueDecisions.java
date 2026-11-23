package forge.web.campaign.rogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.rogue.KeywordHint;
import forge.gamemodes.rogue.PreviewReferenceType;
import forge.gamemodes.rogue.RogueCustomDeckGenerator;
import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueTutorial;
import forge.gamemodes.rogue.TextHelper;
import forge.gamemodes.rogue.effect.BazaarItem;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.ChoiceRerollContext;
import forge.gamemodes.rogue.effect.CursedEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.flow.RogueDecisions;
import forge.gamemodes.rogue.npc.NPCContext;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.util.Localizer;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.images.SkinSprites;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.RogueProtocol.*;
import forge.web.protocol.ToBrowser.CataloguePage;
import forge.web.protocol.ToBrowser.EditorGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Asks the browser. A question waits on the flow thread, with no timeout, until an answer that its check accepts arrives.
 * Answers name the offered objects by index, and are checked against the offer, because they come from outside.
 */
final class WebRogueDecisions implements RogueDecisions {
    /**
     * An open question: what was sent, the check an answer must pass, and the answer once one has passed.
     * offer answers the catalogue's queries for the commanders it offers, or is null.
     */
    record Open(RogueQuestion message, Predicate<JsonElement> valid, CompletableFuture<JsonElement> answer, Function<CatalogueQuery, CataloguePage> offer) {
    }

    /** The catalogue's source that reads the commanders the open question offers. */
    static final String COMMANDERS = "rogue-commanders";

    private static final String REROLL = "reroll";
    private static final String SKIP = "skip";
    /** Desktop's avatar tiles end at index 125, the question mark. */
    private static final int AVATARS = 126;

    private final RogueGame game;
    private int ids;
    /** The picture behind the last NPC's scene, which a boon's result is drawn over. */
    private String boonArt;
    /** The portrait picked with the commander, which choosePortrait answers with. The page asks for both on one screen. */
    private int portrait = -1;

    WebRogueDecisions(final RogueGame game) {
        this.game = game;
    }

    private JsonElement ask(final Record shape, final Predicate<JsonElement> valid) {
        return ask(shape, valid, null);
    }

    private JsonElement ask(final Record shape, final Predicate<JsonElement> valid, final List<PaperCard> offer) {
        final RogueQuestion question = new RogueQuestion(++ids, shape instanceof AskChoice c ? c : null, shape instanceof AskCards c ? c : null,
                shape instanceof AskCommander c ? c : null, shape instanceof AskShop s ? s : null,
                shape instanceof TellOmen o ? o : null, shape instanceof Tell t ? t : null);
        return game.ask(new Open(question, answer -> {
            try {
                return valid.test(answer);
            } catch (final RuntimeException malformed) {
                return false;
            }
        }, new CompletableFuture<>(), offer == null ? null : catalogue(offer)));
    }

    /** Pages through cards with the catalogue's search, each row keyed by the card's index in cards. */
    private static Function<CatalogueQuery, CataloguePage> catalogue(final List<PaperCard> cards) {
        final CardCatalog all = CardCatalog.of(cards);
        final Map<PaperCard, Integer> ids = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            ids.putIfAbsent(cards.get(i), i);
        }
        return q -> all.query(q.request(), new CardCatalog.Query(q.text(), q.colours(), q.type(), q.filters(), q.sort(), q.offset(), true),
                c -> null, null, name -> 0, c -> new CardCatalog.Extra(null, null, null, null, null, String.valueOf(ids.get(c)))).from(COMMANDERS);
    }

    private void tell(final Record shape) {
        ask(shape, JsonElement::isJsonNull);
    }

    private static boolean is(final JsonElement answer, final String word) {
        return answer.isJsonPrimitive() && answer.getAsJsonPrimitive().isString() && word.equals(answer.getAsString());
    }

    /** The index an answer names, or -1 when it names none of size. */
    private static int index(final JsonElement answer, final int size) {
        if (!answer.isJsonPrimitive() || !answer.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        final double value = answer.getAsDouble();
        return value == Math.rint(value) && value >= 0 && value < size ? (int) value : -1;
    }

    /** The distinct indexes an answer names, or null when it is not a list of them. */
    private static List<Integer> indexes(final JsonElement answer, final int size) {
        if (!answer.isJsonArray()) {
            return null;
        }
        final Set<Integer> picked = new LinkedHashSet<>();
        for (final JsonElement e : answer.getAsJsonArray()) {
            final int i = index(e, size);
            if (i < 0 || !picked.add(i)) {
                return null;
            }
        }
        return new ArrayList<>(picked);
    }

    private static <T> List<T> pick(final List<T> offered, final List<Integer> picked) {
        return picked.stream().map(offered::get).collect(Collectors.toList());
    }

    static List<Part> parts(final String raw) {
        final List<Part> parts = new ArrayList<>();
        for (final TextHelper.Part p : TextHelper.parts(raw)) {
            final KeywordHint hint = p.type() == PreviewReferenceType.KEYWORD ? KeywordHint.fromToken(p.token()) : null;
            if (p.type() == PreviewReferenceType.CARD) {
                final PaperCard card = TextHelper.resolveCard(p.token());
                parts.add(new Part("card", p.text(), card == null ? null : card.getImageKey(false), null, null));
            } else if (hint != null) {
                parts.add(new Part("term", p.text(), null, hint.getTitle(), hint.getHintText()));
            } else {
                parts.add(new Part("text", p.text(), null, null, null));
            }
        }
        return parts;
    }

    private static List<RogueCard> cards(final List<PaperCard> cards) {
        return cards.stream().map(c -> new RogueCard(c.getName(), c.getImageKey(false), 1, null)).collect(Collectors.toList());
    }

    private static List<PickCard> pickCards(final List<PaperCard> cards) {
        return IntStream.range(0, cards.size()).mapToObj(i -> {
            final PaperCard c = cards.get(i);
            return new PickCard(i, c.getName(), c.getImageKey(false), c.getRules().getManaCost().getCMC());
        }).collect(Collectors.toList());
    }

    /** The cards an answer picks by their ids in shown, or null when it names others or the choice does not accept them. */
    static List<PaperCard> picked(final CardChoice choice, final List<PaperCard> shown, final JsonElement answer) {
        final List<Integer> ids = indexes(answer, shown.size());
        if (ids == null) {
            return null;
        }
        final List<PaperCard> picked = pick(shown, ids);
        return choice.accepts(picked) ? picked : null;
    }

    /** The deck picks go into or come out of: the one a choice's preview starts from, which is the deck being started, or the run's. */
    private static PickDeck pickDeck(final Deck deck, final boolean starting) {
        final List<EditorGroup> groups = new ArrayList<>(CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS));
        final CardPool commanders = deck.get(DeckSection.Commander);
        if (commanders != null && !commanders.isEmpty()) {
            groups.add(new EditorGroup(text("lblCommander"), CardCatalog.cards(commanders, DeckCatalog.NO_FLAGS)));
        }
        return new PickDeck(text(starting ? "lblRogueStartingDeck" : "lblDeck"),
                deck.getMain().countAll() + (commanders == null ? 0 : commanders.countAll()), groups);
    }

    private static Option option(final RogueEffect effect) {
        return new Option(effect.getDisplayName(), parts(effect.getRawDescription()), true, null);
    }

    private static String text(final String key, final Object... args) {
        return Localizer.getInstance().getMessage(key, args);
    }

    /** Asks for one of options, a reroll or a skip, and gives the index picked, -1 for a reroll and -2 for a skip. */
    private int choose(final AskChoice choice) {
        final JsonElement answer = ask(choice, a -> is(a, REROLL) ? choice.rerolls() > 0
                : is(a, SKIP) ? choice.skip()
                : index(a, choice.options().size()) >= 0 && choice.options().get(index(a, choice.options().size())).enabled());
        return is(answer, REROLL) ? -1 : is(answer, SKIP) ? -2 : index(answer, choice.options().size());
    }

    /** Asks for a commander and its portrait, then for a partner when it can have one. Cancelling either question cancels both. */
    @Override
    public List<PaperCard> chooseCommanders() {
        final List<PaperCard> all = RogueCustomDeckGenerator.getEligibleCommanders();
        if (all.isEmpty()) {
            showMessage(text("lblRogueChooseAnyCommander"), text("lblRogueNoLegalCommanders"));
            return List.of();
        }
        final List<Integer> avatars = IntStream.range(0, Math.min(AVATARS, SkinSprites.avatarCount())).boxed().collect(Collectors.toList());
        final JsonElement first = ask(new AskCommander(text("lblRogueChooseAnyCommander"), all.size(), avatars, false, null),
                a -> a.isJsonNull() || commander(a, all.size()) >= 0 && (avatars.isEmpty() ? a.getAsJsonObject().get("avatar").isJsonNull()
                        : avatars.contains(a.getAsJsonObject().get("avatar").getAsInt())), all);
        if (first.isJsonNull()) {
            return List.of();
        }
        final PaperCard commander = all.get(commander(first, all.size()));
        portrait = avatars.isEmpty() ? -1 : first.getAsJsonObject().get("avatar").getAsInt();
        final List<PaperCard> partners = RogueCustomDeckGenerator.getEligiblePartnerCommanders(commander);
        if (partners.isEmpty()) {
            return List.of(commander);
        }
        final JsonElement second = ask(new AskCommander(text("lblChoosePartnerFor", commander.getName()), partners.size(), List.of(), true,
                commander.getImageKey(false)),
                a -> a.isJsonNull() || is(a, SKIP) || commander(a, partners.size()) >= 0
                        && RogueDecisions.commanders(List.of(commander, partners.get(commander(a, partners.size())))), partners);
        if (second.isJsonNull()) {
            return List.of();
        }
        return is(second, SKIP) ? List.of(commander) : List.of(commander, partners.get(commander(second, partners.size())));
    }

    /** The commander an answer names, or -1 when it names none of size. */
    private static int commander(final JsonElement answer, final int size) {
        return answer.isJsonObject() ? index(answer.getAsJsonObject().get("commander"), size) : -1;
    }

    /** The commander question asked for the portrait too. */
    @Override
    public int choosePortrait(final PaperCard commander, final int current) {
        final int picked = portrait;
        portrait = -1;
        return picked >= 0 ? picked : current;
    }

    @Override
    public List<PaperCard> chooseCards(final CardChoice choice) {
        final List<PaperCard> shown = choice.shown();
        final Deck deck = choice.preview() != null ? choice.preview().apply(List.of()) : choice.run() == null ? null : choice.run().getCurrentDeck();
        final List<PaperCard> commanders = deck == null ? List.of() : deck.getCommanders();
        final JsonElement answer = ask(new AskCards(choice.kind().name(), choice.title(), choice.subtitle(), pickCards(shown),
                choice.min(), choice.max(), null, null, deck == null ? null : pickDeck(deck, choice.preview() != null),
                commanders.isEmpty() ? null : commanders.get(0).getImageKey(false)), a -> picked(choice, shown, a) != null);
        return picked(choice, shown, answer);
    }

    @Override
    public boolean confirmReplaceRun() {
        return choose(new AskChoice("replace", null, text("lblRogueAbandonCurrentRun"), List.of(parts(text("lblRogueAbandonForNewRun"))),
                List.of(new Option(text("lblRogueStartNewRun"), List.of(), true, null), new Option(text("lblCancel"), List.of(), true, null)),
                0, false, null)) == 0;
    }

    @Override
    public Pick<NPCEffect> chooseBoon(final NPCContext context, final ChoiceRerollContext rerolls) {
        final List<NPCContext.NPCChoice> choices = context.choices();
        final List<Option> options = choices.stream().map(c -> new Option(c.label(),
                c.npcEffect() == null ? List.of() : parts(c.npcEffect().getRawDescription()), true, null)).collect(Collectors.toList());
        final PaperCard npc = TextHelper.resolveCard(context.displayName());
        boonArt = npc == null ? null : npc.getImageKey(false);
        final AskChoice choice = new AskChoice(choices.isEmpty() ? "story" : "boon", context.avatarIndex(), context.displayName(),
                context.flavorTextChunks().stream().map(WebRogueDecisions::parts).collect(Collectors.toList()), options, rerolls.remainingRerolls, false,
                boonArt);
        if (choices.isEmpty()) {
            tell(choice);
            return new Pick<>(false, null);
        }
        final int picked = choose(choice);
        return picked == -1 ? new Pick<>(true, null) : new Pick<>(false, choices.get(picked).npcEffect());
    }

    @Override
    public Pick<ChestEffect> chooseChest(final List<ChestEffect> loot, final RogueRun run, final ChoiceRerollContext rerolls) {
        final int picked = choose(new AskChoice("chest", null, text("lblRogueChest"), List.of(parts(text("lblRogueChooseYourLoot"))),
                loot.stream().map(WebRogueDecisions::option).collect(Collectors.toList()), rerolls.remainingRerolls, true, null));
        return picked == -1 ? new Pick<>(true, null) : new Pick<>(false, picked == -2 ? null : loot.get(picked));
    }

    @Override
    public Pick<RogueEvent.EventChoice> chooseEvent(final RogueEvent event, final RogueRun run, final ChoiceRerollContext rerolls) {
        final List<RogueEvent.EventChoice> choices = event.getChoices();
        final List<Option> options = choices.stream().map(c -> {
            final boolean enabled = c.effect().isChoiceAvailable(run);
            return new Option(c.label(), parts(c.effect().getRawDescription()), enabled, enabled ? null : c.effect().getUnavailableReason(run));
        }).collect(Collectors.toList());
        final int picked = choose(new AskChoice("event", null, event.getDisplayName(), List.of(parts(event.getRawDescription())), options,
                rerolls.remainingRerolls, false, null));
        return picked == -1 ? new Pick<>(true, null) : new Pick<>(false, choices.get(picked));
    }

    @Override
    public SanctumAnswer chooseSanctum(final SanctumOffer offer) {
        final List<SanctumOption> offered = offer.options();
        final List<Option> options = offered.stream().map(o -> new Option(o.label(), parts(o.description()), o.enabled(),
                o.enabled() ? null : o.disabledReason())).collect(Collectors.toList());
        final int picked = choose(new AskChoice("sanctum", null, text("lblRogueSanctum"), List.of(parts(text("lblRogueChooseAction"))), options, 0, true, null));
        return picked == -2 ? new SanctumAnswer(SanctumAction.SKIP, null) : offered.get(picked).answer();
    }

    @Override
    public ShopAnswer shop(final ShopOffer offer) {
        final List<BazaarItem> items = offer.items();
        final List<ShopItem> shown = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final BazaarItem item = items.get(i);
            final RogueEffect trait = item.traitEffect();
            shown.add(new ShopItem(i, trait != null ? trait.getDisplayName() : item.card().getName(), item.card() == null ? null : item.card().getImageKey(false),
                    trait != null ? parts(trait.getRawDescription()) : List.of(), item.getPrice()));
        }
        final Function<JsonElement, ShopAnswer> read = a -> {
            final JsonObject o = a.getAsJsonObject();
            final List<Integer> picked = indexes(o.get("selected"), items.size());
            return picked == null ? null : new ShopAnswer(o.get("reroll").getAsBoolean(), new HashSet<>(pick(items, picked)), o.get("tab").getAsInt());
        };
        final JsonElement answer = ask(new AskShop(offer.title(), shown, offer.gold(), offer.rerollLabel() == null ? null : offer.rerollCost(),
                offer.rerollEnabled(), offer.tab()), a -> {
            final ShopAnswer parsed = read.apply(a);
            return parsed != null && offer.accepts(parsed);
        });
        return read.apply(answer);
    }

    @Override
    public List<PaperCard> show(final String title, final List<PaperCard> cards, final int maxSelections, final String rerollLabel,
            final int rerollCost, final boolean rerollEnabled, final int gold) {
        final boolean mayReroll = rerollLabel != null && rerollEnabled;
        final JsonElement answer = ask(new AskCards("REWARD", title, null, pickCards(cards), 0, maxSelections,
                rerollLabel == null ? null : rerollCost, gold, null, null),
                a -> is(a, REROLL) ? mayReroll : indexes(a, cards.size()) != null && indexes(a, cards.size()).size() <= maxSelections);
        return is(answer, REROLL) ? null : pick(cards, indexes(answer, cards.size()));
    }

    @Override
    public <T> List<T> devPick(final String title, final List<T> all, final List<T> current, final int count) {
        if (!ForgePreferences.DEV_MODE) {
            return current;
        }
        final List<Option> options = all.stream().map(t -> t instanceof RogueEffect e ? option(e) : new Option(String.valueOf(t), List.of(), true, null))
                .collect(Collectors.toList());
        final JsonElement answer = ask(new AskChoice("dev", null, title, List.of(), options, 0, true, null),
                a -> is(a, SKIP) || indexes(a, all.size()) != null && indexes(a, all.size()).size() == count);
        return is(answer, SKIP) ? current : pick(all, indexes(answer, all.size()));
    }

    @Override
    public void showPlaneboundEffects(final NodePlanebound node, final List<WrathfulEffect> wrathful, final List<CursedEffect> cursed) {
        final List<Omen> omens = new ArrayList<>();
        wrathful.forEach(w -> omens.add(new Omen("wrathful", w.getDisplayName(), parts(w.getRawDescription()))));
        cursed.forEach(c -> omens.add(new Omen("cursed", c.getDisplayName(), parts(c.getRawDescription()))));
        final Heading heading = RogueDecisions.planeboundEffects(wrathful, cursed);
        tell(new TellOmen(heading.title(), heading.text(), omens));
    }

    @Override
    public void showResult(final ResultKind kind, final String title, final String text, final List<Section> sections) {
        if (kind == ResultKind.SPARK) {
            game.announce(new RogueAnnounce("spark", title, text, null));
            return;
        }
        tell(new Tell(kind.name(), title, parts(text), sections.stream()
                .map(s -> new TellSection(s.label(), parts(s.text()), s.cards() == null ? List.of() : cards(s.cards()))).collect(Collectors.toList()),
                kind == ResultKind.BOON ? boonArt : null));
    }

    @Override
    public void showMessage(final String title, final String text) {
        tell(new Tell("MESSAGE", title, parts(text), List.of(), null));
    }

    /** Tutorials are not drawn on the web yet, so none is marked seen. */
    @Override
    public void showTutorial(final RogueTutorial tutorial) {
    }

    @Override
    public void startMatch(final RogueMatch match) {
        throw new UnsupportedOperationException("Rogue Commander battles cannot be played in the browser yet");
    }

    @Override
    public void mapChanged(final RogueRun run) {
    }

    @Override
    public void runStarted(final RogueRun run) {
        game.runStarted();
    }

    @Override
    public void runEnded() {
        game.runEnded();
    }
}
