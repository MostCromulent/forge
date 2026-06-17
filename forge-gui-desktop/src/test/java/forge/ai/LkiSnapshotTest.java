package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class LkiSnapshotTest extends AITest {

    private Card findInSnapshot(CardCollectionView snap, String name) {
        for (Card c : snap) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // Core invariant of the dirty-gated snapshot: getLastStateBattlefield must always mirror live state —
    // it must not go stale after an in-place change to a present card (the dirty flag must force a rebuild).
    @Test
    public void snapshotMirrorsLiveAfterInPlaceChange() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card bear = addCard("Runeclaw Bear", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        Card lkiBear = findInSnapshot(game.copyLastStateBattlefield(), "Runeclaw Bear");
        assertNotNull(lkiBear, "bear missing from snapshot");
        assertEquals(lkiBear.isTapped(), bear.isTapped(), "snapshot != live at baseline");

        bear.setTapped(true);
        Card lkiBear2 = findInSnapshot(game.copyLastStateBattlefield(), "Runeclaw Bear");
        assertNotNull(lkiBear2);
        assertEquals(lkiBear2.isTapped(), true, "snapshot stale after tap — did not mirror live");
    }

    // The cascade case the optimization targets: tokens entering during a non-departure sequence must be
    // appended to the snapshot (it must accumulate them, exactly as the old per-resolution rebuild did).
    @Test
    public void snapshotAccumulatesEnteringTokens() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        addCard("Runeclaw Bear", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        game.copyLastState(); // build initial snapshot

        int before = game.copyLastStateBattlefield().size();
        // route a real entry through changeZone so the incremental append hook fires
        Card ogre = createCard("Hill Giant", ai);
        ogre.setGameTimestamp(game.getNextTimestamp());
        ai.getZone(forge.game.zone.ZoneType.Hand).add(ogre);
        game.getAction().moveToPlay(ogre, null, forge.game.ability.AbilityKey.newMap());

        assertNotNull(findInSnapshot(game.copyLastStateBattlefield(), "Hill Giant"),
                "card entering via changeZone was not appended to the snapshot");
        assertEquals(game.copyLastStateBattlefield().size(), before + 1, "snapshot did not accumulate the new entry");
    }

    // Re-attaching equipment between in-play creatures rewrites the attachment graph without a zone change;
    // the snapshot must reflect the new attachment (else dies-triggers / death-LKI read the wrong target).
    @Test
    public void snapshotReflectsReattachment() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card bearA = addCard("Runeclaw Bear", ai);
        Card bearB = addCard("Hill Giant", ai);
        Card equip = addCard("Bonesplitter", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        equip.attachToEntity(bearA, null, true);
        Card lkiEquip = findInSnapshot(game.copyLastStateBattlefield(), "Bonesplitter");
        assertNotNull(lkiEquip);
        assertNotNull(lkiEquip.getEntityAttachedTo(), "snapshot shows equipment unattached after equip");
        assertEquals(lkiEquip.getEntityAttachedTo().getId(), bearA.getId(), "snapshot attached to wrong creature");

        // re-attach to the other creature; snapshot must follow
        equip.attachToEntity(bearB, null, true);
        Card lkiEquip2 = findInSnapshot(game.copyLastStateBattlefield(), "Bonesplitter");
        assertNotNull(lkiEquip2.getEntityAttachedTo());
        assertEquals(lkiEquip2.getEntityAttachedTo().getId(), bearB.getId(), "snapshot stale after re-attachment");
    }
}
