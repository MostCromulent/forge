package forge.web.campaign.rogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import forge.web.images.SkinSprites;
import forge.web.protocol.RogueProtocol.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    /** An open question: what was sent, the check an answer must pass, and the answer once one has passed. */
    record Open(RogueQuestion message, Predicate<JsonElement> valid, CompletableFuture<JsonElement> answer) {
    }

    private static final String REROLL = "reroll";
    private static final String SKIP = "skip";
    /** Desktop's avatar tiles end at index 125, the question mark. */
    private static final int AVATARS = 126;

    private final RogueGame game;
    private int ids;

    WebRogueDecisions(final RogueGame game) {
        this.game = game;
    }

    private JsonElement ask(final String kind, final Record shape, final Predicate<JsonElement> valid) {
        final RogueQuestion question = new RogueQuestion(++ids, kind, shape instanceof AskChoice c ? c : null, shape instanceof AskCards c ? c : null,
                shape instanceof AskCommander c ? c : null, shape instanceof AskPortrait p ? p : null, shape instanceof AskShop s ? s : null,
                shape instanceof TellOmen o ? o : null, shape instanceof Tell t ? t : null);
        return game.ask(new Open(question, answer -> {
            try {
                return valid.test(answer);
            } catch (final RuntimeException malformed) {
                return false;
            }
        }, new CompletableFuture<>()));
    }

    private void tell(final String kind, final Record shape) {
        ask(kind, shape, JsonElement::isJsonNull);
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
            if (p.type() == PreviewReferenceType.CARD) {
                final PaperCard card = TextHelper.resolveCard(p.token());
                parts.add(new Part("card", p.text(), card == null ? null : card.getImageKey(false), null));
            } else if (p.type() == PreviewReferenceType.KEYWORD) {
                parts.add(new Part("term", p.text(), null, p.token()));
            } else {
                parts.add(new Part("text", p.text(), null, null));
            }
        }
        return parts;
    }

    private static List<RogueCard> cards(final List<PaperCard> cards) {
        return cards.stream().map(c -> new RogueCard(c.getName(), c.getImageKey(false))).collect(Collectors.toList());
    }

    private static Option option(final RogueEffect effect) {
        return new Option(effect.getDisplayName(), parts(effect.getRawDescription()), true, null);
    }

    private static String text(final String key, final Object... args) {
        return Localizer.getInstance().getMessage(key, args);
    }

    /** Asks for one of options, a reroll or a skip, and gives the index picked, -1 for a reroll and -2 for a skip. */
    private int choose(final AskChoice choice) {
        final JsonElement answer = ask(choice.kind(), choice, a -> is(a, REROLL) ? choice.rerolls() > 0
                : is(a, SKIP) ? choice.skip()
                : index(a, choice.options().size()) >= 0 && choice.options().get(index(a, choice.options().size())).enabled());
        return is(answer, REROLL) ? -1 : is(answer, SKIP) ? -2 : index(answer, choice.options().size());
    }

    @Override
    public List<PaperCard> chooseCommanders() {
        final List<PaperCard> all = RogueCustomDeckGenerator.getEligibleCommanders();
        if (all.isEmpty()) {
            showMessage(text("lblRogueChooseAnyCommander"), text("lblRogueNoLegalCommanders"));
            return List.of();
        }
        final JsonElement answer = ask("commander", new AskCommander(text("lblRogueChooseAnyCommander"), cards(all)), a -> {
            final List<Integer> picked = indexes(a, all.size());
            return picked != null && (picked.isEmpty() || RogueDecisions.commanders(pick(all, picked)));
        });
        return pick(all, indexes(answer, all.size()));
    }

    @Override
    public int choosePortrait(final PaperCard commander, final int current) {
        final List<Integer> avatars = IntStream.range(0, Math.min(AVATARS, SkinSprites.avatarCount())).boxed().collect(Collectors.toList());
        final JsonElement answer = ask("portrait", new AskPortrait(commander.getName(), avatars.contains(current) ? current : 0, avatars),
                a -> index(a, avatars.size()) >= 0);
        return index(answer, avatars.size());
    }

    @Override
    public List<PaperCard> chooseCards(final CardChoice choice) {
        final List<PaperCard> offered = choice.candidates();
        final JsonElement answer = ask("cards", new AskCards(choice.kind().name(), choice.title(), choice.subtitle(), cards(offered),
                choice.min(), choice.max(), null, null), a -> {
            final List<Integer> picked = indexes(a, offered.size());
            return picked != null && choice.accepts(pick(offered, picked));
        });
        return pick(offered, indexes(answer, offered.size()));
    }

    @Override
    public boolean confirmReplaceRun() {
        return choose(new AskChoice("replace", null, text("lblRogueAbandonCurrentRun"), List.of(parts(text("lblRogueAbandonForNewRun"))),
                List.of(new Option(text("lblRogueStartNewRun"), List.of(), true, null), new Option(text("lblCancel"), List.of(), true, null)),
                0, false)) == 0;
    }

    @Override
    public Pick<NPCEffect> chooseBoon(final NPCContext context, final ChoiceRerollContext rerolls) {
        final List<NPCContext.NPCChoice> choices = context.choices();
        final List<Option> options = choices.stream().map(c -> new Option(c.label(),
                c.npcEffect() == null ? List.of() : parts(c.npcEffect().getRawDescription()), true, null)).collect(Collectors.toList());
        final AskChoice choice = new AskChoice(choices.isEmpty() ? "story" : "boon", context.avatarIndex(), context.displayName(),
                context.flavorTextChunks().stream().map(WebRogueDecisions::parts).collect(Collectors.toList()), options, rerolls.remainingRerolls, false);
        if (choices.isEmpty()) {
            tell(choice.kind(), choice);
            return new Pick<>(false, null);
        }
        final int picked = choose(choice);
        return picked == -1 ? new Pick<>(true, null) : new Pick<>(false, choices.get(picked).npcEffect());
    }

    @Override
    public Pick<ChestEffect> chooseChest(final List<ChestEffect> loot, final RogueRun run, final ChoiceRerollContext rerolls) {
        final int picked = choose(new AskChoice("chest", null, text("lblRogueChest"), List.of(parts(text("lblRogueChooseYourLoot"))),
                loot.stream().map(WebRogueDecisions::option).collect(Collectors.toList()), rerolls.remainingRerolls, true));
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
                rerolls.remainingRerolls, false));
        return picked == -1 ? new Pick<>(true, null) : new Pick<>(false, choices.get(picked));
    }

    @Override
    public SanctumAnswer chooseSanctum(final SanctumOffer offer) {
        final List<SanctumOption> offered = offer.options();
        final List<Option> options = offered.stream().map(o -> new Option(o.label(), parts(o.description()), o.enabled(),
                o.enabled() ? null : o.disabledReason())).collect(Collectors.toList());
        final int picked = choose(new AskChoice("sanctum", null, text("lblRogueSanctum"), List.of(parts(text("lblRogueChooseAction"))), options, 0, true));
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
        final JsonElement answer = ask("shop", new AskShop(offer.title(), shown, offer.gold(), offer.rerollLabel() == null ? null : offer.rerollCost(),
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
        final JsonElement answer = ask("reward", new AskCards("REWARD", title, null, cards(cards), 0, maxSelections,
                rerollLabel == null ? null : rerollCost, gold),
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
        final JsonElement answer = ask("dev", new AskChoice("dev", null, title, List.of(), options, 0, true),
                a -> is(a, SKIP) || indexes(a, all.size()) != null && indexes(a, all.size()).size() == count);
        return is(answer, SKIP) ? current : pick(all, indexes(answer, all.size()));
    }

    @Override
    public void showPlaneboundEffects(final NodePlanebound node, final List<WrathfulEffect> wrathful, final List<CursedEffect> cursed) {
        final List<Omen> omens = new ArrayList<>();
        wrathful.forEach(w -> omens.add(new Omen("wrathful", w.getDisplayName(), parts(w.getRawDescription()))));
        cursed.forEach(c -> omens.add(new Omen("cursed", c.getDisplayName(), parts(c.getRawDescription()))));
        final Heading heading = RogueDecisions.planeboundEffects(wrathful, cursed);
        tell("omen", new TellOmen(heading.title(), heading.text(), omens));
    }

    @Override
    public void showResult(final ResultKind kind, final String title, final String text, final List<Section> sections) {
        if (kind == ResultKind.SPARK) {
            game.announce(new RogueAnnounce("spark", title, text, null));
            return;
        }
        tell("result", new Tell(kind.name(), null, title, parts(text), List.of(), sections.stream()
                .map(s -> new TellSection(s.label(), parts(s.text()), s.cards() == null ? List.of() : cards(s.cards()))).collect(Collectors.toList())));
    }

    @Override
    public void showMessage(final String title, final String text) {
        tell("message", new Tell("MESSAGE", null, title, parts(text), List.of(), List.of()));
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
