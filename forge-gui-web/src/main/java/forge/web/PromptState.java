package forge.web;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.gamemodes.net.DeltaPacket;
import forge.util.Localizer;
import forge.web.ToBrowser.Prompt;
import forge.web.ToBrowser.PromptButton;
import forge.web.ToBrowser.Ref;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The prompt console: its message, its two buttons, and what may be clicked. The engine changes it piece by piece,
 * from the dispatch thread and from AbstractGuiGame's final timer methods on the host UI thread, so every change
 * is made and sent under one lock, and the browser sees each prompt in the order it came about.
 */
final class PromptState {
    private final Consumer<Prompt> send;
    private String message = "";
    /** "toss" or "lastGame" while the player is asked who starts, having won the toss or lost the last game. */
    private String starterChoice;
    private boolean priority;
    private Ref card;
    private PromptButton ok = new PromptButton("", false);
    private PromptButton cancel = new PromptButton("", false);
    private boolean focusOk;
    private boolean paying;
    private List<Ref> selectable = List.of();
    private int selectableMin;
    private List<Ref> selectablePlayers = List.of();
    private final Set<Integer> highlighted = new LinkedHashSet<>();

    PromptState(final Consumer<Prompt> send) {
        this.send = send;
    }

    /** The prompt as it stands, sent to one browser, as a reconnecting one needs. */
    synchronized void sendTo(final Consumer<Prompt> to) {
        to.accept(current());
    }

    /** Whether the prompt now showing gives the player priority. */
    synchronized boolean priority() {
        return priority;
    }

    synchronized void message(final String text, final CardView about) {
        final String trimmed = withoutTurnState(text);
        message = trimmed;
        priority = !trimmed.equals(text);
        starterChoice = opensWith(trimmed, "lblYouHaveWonTheCoinToss") ? "toss"
                : opensWith(trimmed, "lblYouLostTheLastGame") ? "lastGame" : null;
        card = cardRef(about);
        changed();
    }

    synchronized void buttons(final String label1, final String label2, final boolean enable1, final boolean enable2,
            final boolean focus1) {
        ok = new PromptButton(label1, enable1);
        cancel = new PromptButton(label2, enable2);
        focusOk = focus1;
        // Only a mana payment offers Auto, and the browser holds the card being paid for while it does
        paying = Localizer.getInstance().getMessage("lblAuto").equals(label1);
        changed();
    }

    synchronized void selectable(final List<Ref> cards, final int min) {
        selectable = cards;
        selectableMin = min;
        changed();
    }

    synchronized void selectablePlayers(final List<Ref> players) {
        selectablePlayers = players;
        changed();
    }

    synchronized void clearSelectables() {
        selectable = List.of();
        selectablePlayers = List.of();
        selectableMin = 0;
        changed();
    }

    synchronized void highlight(final Iterable<GameEntityView> entities, final boolean on) {
        for (final GameEntityView e : entities) {
            final int key = DeltaPacket.makeDeltaKey(e instanceof CardView ? DeltaPacket.TYPE_CARD_VIEW : DeltaPacket.TYPE_PLAYER_VIEW, e.getId());
            if (on) {
                highlighted.add(key);
            } else {
                highlighted.remove(key);
            }
        }
        changed();
    }

    synchronized void card(final CardView about) {
        card = cardRef(about);
        changed();
    }

    private void changed() {
        send.accept(current());
    }

    private Prompt current() {
        return new Prompt(message, priority, card, ok, cancel, focusOk, paying, selectable, selectableMin,
                selectablePlayers, List.copyOf(highlighted), starterChoice);
    }

    /** Whether the message opens with the line the key makes, whatever player's name fills it. */
    private static boolean opensWith(final String message, final String key) {
        final String[] around = Localizer.getInstance().getMessage(key, "\u0000").split("\u0000", -1);
        final String first = message.split("\n", 2)[0];
        return around.length == 2 && first.startsWith(around[0]) && first.endsWith(around[1]);
    }

    static Ref cardRef(final CardView card) {
        return card == null ? null : Ref.card(card.getId());
    }

    // The phase pill and the stack pile carry the turn, the step and what is waiting, so the priority prompt
    // keeps only the lines that add something, such as the storm count or a macro being recorded
    private static String withoutTurnState(final String message) {
        final Localizer loc = Localizer.getInstance();
        if (!message.startsWith(loc.getMessage("lblPriority") + ":")) {
            return message;
        }
        final List<String> labels = List.of(loc.getMessage("lblPriority"), loc.getMessage("lblTurn"),
                loc.getMessage("lblPhase"), loc.getMessage("lblStack"));
        final StringBuilder kept = new StringBuilder();
        for (final String line : message.split("\n")) {
            if (line.isBlank() || labels.stream().anyMatch(label -> line.startsWith(label + ":"))) {
                continue;
            }
            kept.append(kept.isEmpty() ? "" : "\n").append(line);
        }
        return kept.toString();
    }
}
