package forge.web.match;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableObject;
import forge.util.Localizer;
import forge.web.protocol.ToBrowser.Prompt;
import forge.web.protocol.ToBrowser.PromptButton;
import forge.web.protocol.ToBrowser.Ref;
import forge.web.protocol.ToBrowser.StarterChoice;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** The prompt console, changed from the dispatch thread and the host UI thread, so every change is made and sent under one lock. */
final class PromptState {
    private final Consumer<Prompt> send;
    private String message = "";
    /** "toss" or "lastGame" while the player is asked who starts, having won the toss or lost the last game. */
    private StarterChoice starterChoice;
    private boolean priority;
    private Ref card;
    private PromptButton ok = new PromptButton("", false);
    private PromptButton cancel = new PromptButton("", false);
    private boolean focusOk;
    private boolean paying;
    private List<Ref> selectable = List.of();
    private int selectableMin;
    private List<Ref> selectablePlayers = List.of();
    private List<Integer> selectableStack = List.of();
    private final Set<Integer> highlighted = new LinkedHashSet<>();

    PromptState(final Consumer<Prompt> send) {
        this.send = send;
    }

    /** The prompt as it stands, sent to one browser, as a reconnecting one needs. */
    synchronized void sendTo(final Consumer<Prompt> to) {
        to.accept(current());
    }

    synchronized void message(final String text, final CardView about) {
        final String trimmed = withoutTurnState(text);
        message = trimmed;
        priority = !trimmed.equals(text);
        starterChoice = opensWith(trimmed, "lblYouHaveWonTheCoinToss") ? StarterChoice.toss
                : opensWith(trimmed, "lblYouLostTheLastGame") ? StarterChoice.lastGame : null;
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

    synchronized void selectableTargets(final List<Ref> players, final List<Integer> stackItems) {
        selectablePlayers = players;
        selectableStack = stackItems;
        changed();
    }

    synchronized void clearSelectables() {
        selectable = List.of();
        selectablePlayers = List.of();
        selectableStack = List.of();
        selectableMin = 0;
        changed();
    }

    synchronized void highlight(final Iterable<? extends TrackableObject> objects, final boolean on) {
        for (final TrackableObject e : objects) {
            final int type = e instanceof CardView ? DeltaPacket.TYPE_CARD_VIEW
                    : e instanceof PlayerView ? DeltaPacket.TYPE_PLAYER_VIEW : DeltaPacket.TYPE_STACK_ITEM_VIEW;
            final int key = DeltaPacket.makeDeltaKey(type, e.getId());
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
                selectablePlayers, selectableStack, List.copyOf(highlighted), starterChoice);
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

    // Drops the priority prompt's lines that the phase pill and the stack pile already show
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
