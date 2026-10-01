package forge.web;

import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.web.ToBrowser.CardMoved;
import forge.web.ToBrowser.Ref;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BrowserEventsTest {
    /**
     * A spell cast from the hand is copied as it moves, so netplay delivers the event's card as a snapshot with no
     * controller. The browser shows its own spell as awaiting payment only when the move names a caster.
     */
    @Test
    public void castSnapshotNamesItsCasterFromTheTracker() {
        final Tracker tracker = new Tracker();
        final PlayerView caster = new PlayerView(7, tracker);
        final CardView onStack = new CardView(51, tracker, "Floodpits Drowner");
        onStack.set(TrackableProperty.Controller, caster);
        tracker.putObj(TrackableTypes.CardViewType, 51, onStack);
        final CardView snapshot = new CardView(51, tracker, "Floodpits Drowner");

        final Object moved = BrowserEvents.forwarded(new GameEventCardChangeZone(snapshot,
                new ZoneView(caster, ZoneType.Hand), new ZoneView(null, ZoneType.Stack)), tracker);

        Assert.assertEquals(((CardMoved) moved).caster(), Ref.player(7));
    }
}
