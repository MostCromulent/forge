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
import forge.util.Localizer;
import forge.web.ToBrowser.Fetched;
import forge.web.ToBrowser.ImportFix;
import forge.web.ToBrowser.ImportLine;
import forge.web.ToBrowser.ImportProblem;
import forge.web.ToBrowser.ImportResult;
import forge.web.ToBrowser.ImportSummary;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads a deck list for the browser, taking whether each card is allowed from Legality so its marks and the editor's verdict agree. */
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
        return read(text, check, null);
    }

    /** With a collection, a card not owned is a problem, and a collection that fixes all but the main deck takes the main deck alone. */
    static Read read(final String text, final Check check, final DeckEditor.Collection collection) {
        final Set<String> owned = new HashSet<>();
        if (collection != null) {
            collection.cards().get().forEach(e -> owned.add(e.getKey().getName()));
        }
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
        // A deck file is read as Forge reads its own decks, since the list recognizer does not know its card lines
        final boolean file = Arrays.stream(raw).anyMatch(l -> l.trim().equalsIgnoreCase("[metadata]"));
        DeckSection fileSection = null;
        boolean fileHead = false;
        for (int i = 0; i < raw.length; i++) {
            final String line = raw[i].trim();
            final CardPool listed = new CardPool();
            final DeckSection to;
            if (file) {
                if (line.startsWith("[") && line.endsWith("]")) {
                    final String heading = line.substring(1, line.length() - 1);
                    fileSection = DeckSection.smartValueOf(heading);
                    fileHead = "metadata".equalsIgnoreCase(heading);
                    kinds[i] = fileSection == null ? IGNORED : HEADING;
                    continue;
                }
                if (fileHead && line.startsWith("Name=")) {
                    kinds[i] = HEADING;
                    name = line.substring("Name=".length());
                    continue;
                }
                if (fileSection != null) {
                    listed.addAll(CardPool.fromCardList(List.of(line)));
                }
                if (listed.isEmpty()) {
                    kinds[i] = IGNORED;
                    continue;
                }
                // A card Forge does not have is read as a stand-in, which no game can play
                if (listed.toFlatList().get(0).getRules().isUnsupported()) {
                    kinds[i] = PROBLEM;
                    notImported++;
                    problems.add(unknown(i, line.replaceFirst("\\|.*$", ""), false));
                    continue;
                }
                to = fileSection;
            } else {
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
                        continue;
                    }
                    case DECK_SECTION_NAME -> {
                        kinds[i] = HEADING;
                        section = DeckSection.valueOf(token.getText());
                        continue;
                    }
                    case UNKNOWN_CARD, UNSUPPORTED_CARD -> {
                        kinds[i] = PROBLEM;
                        notImported++;
                        problems.add(unknown(i, line, token.getType() == TokenType.UNSUPPORTED_CARD));
                        continue;
                    }
                    default -> {
                        if (!CARDS.contains(token.getType())) {
                            kinds[i] = IGNORED;
                            continue;
                        }
                        listed.add(token.getCard(), token.getQuantity());
                        to = token.getTokenSection();
                    }
                }
            }
            if (collection != null && collection.mainOnly() && to != DeckSection.Main) {
                kinds[i] = IGNORED;
                continue;
            }
            final PaperCard first = listed.toFlatList().get(0);
            // The listed printing stands for the name: the editor takes the printings owned when the deck is made
            final boolean taken = collection == null || first.getRules().getType().isBasicLand() || owned.contains(first.getName());
            if (!taken) {
                kinds[i] = PROBLEM;
                notImported++;
                problems.add(new ImportProblem(i, Localizer.getInstance().getMessage("lblWebImportLineCard", i + 1, first.getName()),
                        Localizer.getInstance().getMessage(DeckEditor.NOT_OWNED),
                        List.of(new ImportFix("leaveOut", Localizer.getInstance().getMessage("lblWebImportLeaveOut"), null))));
                continue;
            }
            kinds[i] = READ;
            deck.getOrCreate(to).addAll(listed);
            lineOf.putIfAbsent(first.getName(), i);
        }
        final String chosen = chooseCommander(deck, check, problems);
        final Map<String, String> flags = Legality.check(deck, check).flags();
        final List<ImportProblem> cardProblems = new ArrayList<>();
        flags.forEach((card, flag) -> {
            final Integer at = lineOf.get(card);
            if (at != null) {
                kinds[at] = PROBLEM;
                cardProblems.add(new ImportProblem(at, Localizer.getInstance().getMessage("lblWebImportLineCard", at + 1, card),
                        Localizer.getInstance().getMessage("lblWebLegalitySentence", StringUtils.capitalize(flag)),
                        List.of(new ImportFix("leaveOut", Localizer.getInstance().getMessage("lblWebImportLeaveOut"), null))));
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

    /** A line that names no card: with the closest known name offered, when one is close enough to be the one meant. */
    private static ImportProblem unknown(final int line, final String text, final boolean unsupported) {
        final String title = Localizer.getInstance().getMessage("lblWebImportLineText", line + 1, text);
        if (unsupported) {
            return new ImportProblem(line, title, Localizer.getInstance().getMessage("lblWebImportUnsupported"), List.of(new ImportFix("leaveOut", Localizer.getInstance().getMessage("lblWebImportLeaveOut"), null)));
        }
        final String closest = CardCatalog.get().closestName(text.replaceFirst("^\\s*\\d+x?\\s+", "").replaceFirst("\\s*[\\[(].*$", ""));
        final List<ImportFix> fixes = new ArrayList<>();
        if (closest != null) {
            fixes.add(new ImportFix("use", Localizer.getInstance().getMessage("lblWebImportUse", closest), closest));
        }
        fixes.add(new ImportFix("leaveOut", Localizer.getInstance().getMessage("lblWebImportLeaveOut"), null));
        return new ImportProblem(line, title, Localizer.getInstance().getMessage("lblWebImportNotACard"), fixes);
    }

    /** With no Commander section, a lone card that could lead the deck is made its commander and named, and several are offered as a choice. */
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
        fixes.add(new ImportFix("other", Localizer.getInstance().getMessage("lblWebImportOther"), null));
        problems.add(new ImportProblem(-1, Localizer.getInstance().getMessage("lblWebImportNoCommander"), candidates.isEmpty()
                ? Localizer.getInstance().getMessage("lblWebImportNoLeader")
                : Localizer.getInstance().getMessage("lblWebImportNoCommanderSection"), fixes));
        return null;
    }
}
