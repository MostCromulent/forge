package forge.web;

import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;
import forge.deck.DeckSection;
import forge.deck.DeckUrlLoader;
import forge.item.PaperCard;
import forge.web.ToBrowser.Fetched;
import forge.web.ToBrowser.ImportFix;
import forge.web.ToBrowser.ImportLine;
import forge.web.ToBrowser.ImportProblem;
import forge.web.ToBrowser.ImportResult;
import forge.web.ToBrowser.ImportSummary;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a deck list the way the browser shows it: one mark per line of the text, the problems with a fix for each, and
 * the deck the list makes. Cards are recognised by the engine's DeckRecognizer; whether each is allowed comes from
 * Legality, so the marks here and the editor's verdict never disagree.
 */
final class DeckImport {
    private static final String READ = "read";
    private static final String PROBLEM = "problem";
    private static final String IGNORED = "ignored";
    private static final String HEADING = "heading";
    private static final int MOST_COMMANDER_CHOICES = 4;
    private static final Set<TokenType> CARDS = EnumSet.of(TokenType.LEGAL_CARD, TokenType.LIMITED_CARD,
            TokenType.CARD_FROM_NOT_ALLOWED_SET, TokenType.CARD_FROM_INVALID_SET);

    private DeckImport() {
    }

    /** What reading a list found, and the deck it makes. commanderChosen says the commander was picked because only one card could be it. */
    record Read(List<ImportLine> lines, List<ImportProblem> problems, Deck deck, String name, String commanderChosen,
            int notImported) {
    }

    static Read read(final String text, final Check check) {
        final String[] raw = text.split("\r?\n", -1);
        // Lines the recognizer misses only because of a set code and collector number are read by name
        final String[] lines = DeckUrlLoader.getRecognizableImportLines(new DeckRecognizer(), String.join("\n", raw));
        final DeckRecognizer recognizer = new DeckRecognizer();
        recognizer.forceImportBannedAndRestrictedCards();
        final Deck deck = new Deck("");
        final String[] kinds = new String[raw.length];
        final Map<String, Integer> lineOf = new HashMap<>();
        final List<ImportProblem> problems = new ArrayList<>();
        String name = null;
        int notImported = 0;
        // Reading starts in the main deck, so a legendary card before any heading stays there and the commander is chosen below
        DeckSection section = DeckSection.Main;
        for (int i = 0; i < raw.length; i++) {
            final String line = raw[i].trim();
            if (line.startsWith("//")) {
                // A comment, unless it is the "// Name:" line some sites put first
                final String named = DeckRecognizer.deckNameMatch(line);
                kinds[i] = named.isEmpty() ? IGNORED : HEADING;
                name = named.isEmpty() ? name : named;
                continue;
            }
            final Token token = i < lines.length ? recognizer.recognizeLine(lines[i], section) : null;
            if (token == null) {
                kinds[i] = IGNORED;
                continue;
            }
            switch (token.getType()) {
                case DECK_NAME -> {
                    kinds[i] = HEADING;
                    name = token.getText();
                }
                case DECK_SECTION_NAME -> {
                    kinds[i] = HEADING;
                    section = DeckSection.valueOf(token.getText());
                }
                case UNKNOWN_CARD, UNSUPPORTED_CARD -> {
                    kinds[i] = PROBLEM;
                    notImported++;
                    problems.add(unknown(i, line, token.getType() == TokenType.UNSUPPORTED_CARD));
                }
                default -> {
                    if (!CARDS.contains(token.getType())) {
                        kinds[i] = IGNORED;
                        continue;
                    }
                    kinds[i] = READ;
                    final PaperCard card = token.getCard();
                    deck.getOrCreate(token.getTokenSection()).add(card, token.getQuantity());
                    lineOf.putIfAbsent(card.getName(), i);
                }
            }
        }
        final String chosen = chooseCommander(deck, check, problems);
        final Map<String, String> flags = Legality.check(deck, check).flags();
        final List<ImportProblem> cardProblems = new ArrayList<>();
        flags.forEach((card, flag) -> {
            final Integer at = lineOf.get(card);
            if (at != null) {
                kinds[at] = PROBLEM;
                cardProblems.add(new ImportProblem(at, "Line " + (at + 1) + ": " + card, capitalised(flag) + ".",
                        List.of(new ImportFix("leaveOut", "Leave out", null))));
            }
        });
        problems.addAll(cardProblems);
        problems.sort((a, b) -> Integer.compare(a.line(), b.line()));
        final List<ImportLine> marks = new ArrayList<>();
        for (final String kind : kinds) {
            marks.add(new ImportLine(kind));
        }
        deck.setName(name == null ? "" : name.trim());
        return new Read(marks, problems, deck, name == null ? null : name.trim(), chosen, notImported);
    }

    static ImportResult result(final int request, final Read read, final Check check, final Fetched fetched) {
        final Deck deck = read.deck();
        final Legality.Result legality = Legality.check(deck, check);
        final List<PaperCard> commanders = Legality.commanders(deck, check);
        final CardPool commanderSection = deck.get(DeckSection.Commander);
        final CardPool sideboard = deck.get(DeckSection.Sideboard);
        final String colours = commanders.isEmpty() ? DeckCatalog.colors(deck) : Legality.identityLetters(commanders);
        final ImportSummary summary = new ImportSummary(
                deck.getMain().countAll() + (commanderSection == null ? 0 : commanderSection.countAll()),
                sideboard == null ? 0 : sideboard.countAll(), read.notImported(),
                commanders.isEmpty() ? null : commanders.get(0).getName(), read.commanderChosen() != null,
                colours, legality.verdict(), DeckEditor.groups(deck.getMain(), legality),
                DeckEditor.cards(sideboard, legality));
        return new ImportResult(request, read.lines(), read.problems(), summary, read.name(), fetched);
    }

    /** The known card name closest to an unknown one, or null when none is close enough to be the one meant. */
    static String closestName(final String unknown) {
        return CardCatalog.get().closestName(unknown);
    }

    private static ImportProblem unknown(final int line, final String text, final boolean unsupported) {
        final String title = "Line " + (line + 1) + ": \"" + text + "\"";
        if (unsupported) {
            return new ImportProblem(line, title, "Forge can't play this card.", List.of(new ImportFix("leaveOut", "Leave out", null)));
        }
        final String closest = closestName(text.replaceFirst("^\\s*\\d+x?\\s+", "").replaceFirst("\\s*[\\[(].*$", ""));
        final List<ImportFix> fixes = new ArrayList<>();
        if (closest != null) {
            fixes.add(new ImportFix("use", "Use " + closest, closest));
        }
        fixes.add(new ImportFix("leaveOut", "Leave out", null));
        return new ImportProblem(line, title, "Not a card name.", fixes);
    }

    /**
     * A list for a commander format with no Commander section: one card that could lead it is made the commander, and
     * several are offered as a choice. Returns the name chosen, or null.
     */
    private static String chooseCommander(final Deck deck, final Check check, final List<ImportProblem> problems) {
        final DeckFormat df = check.deckFormat();
        final CardPool commanders = deck.get(DeckSection.Commander);
        if (!df.hasCommander() || (commanders != null && !commanders.isEmpty())) {
            return null;
        }
        final List<PaperCard> candidates = new ArrayList<>();
        for (final Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            final CardRules rules = e.getKey().getRules();
            if (rules.getType().isLegendary() && df.isLegalCommander(rules) && !rules.getType().hasSubtype("Background")
                    && candidates.stream().noneMatch(c -> c.getName().equals(e.getKey().getName()))) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.size() == 1) {
            final PaperCard only = candidates.get(0);
            deck.getMain().remove(only, 1);
            deck.getOrCreate(DeckSection.Commander).add(only, 1);
            return only.getName();
        }
        final List<ImportFix> fixes = new ArrayList<>();
        for (final PaperCard c : candidates.subList(0, Math.min(MOST_COMMANDER_CHOICES, candidates.size()))) {
            fixes.add(new ImportFix("commander", c.getName(), c.getName()));
        }
        fixes.add(new ImportFix("other", "Other…", null));
        problems.add(new ImportProblem(-1, "No commander", candidates.isEmpty()
                ? "The list has no card that can lead it. Choose one in the editor."
                : "The list has no Commander section. Pick one of its legendary creatures.", fixes));
        return null;
    }

    private static String capitalised(final String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
