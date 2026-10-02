package forge.web;

import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Conquests made for a test, in the profile of whoever runs it, and removed afterwards. */
final class ConquestFixture {
    private static final List<ConquestData> made = new ArrayList<>();
    private static String currentBefore;

    private ConquestFixture() {
    }

    static String name() {
        return "Conquest test " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A new conquest on a plane, with the plane's first commander and that commander's first planeswalker. */
    static ConquestData create(final String planeName) {
        return create(planeName, name());
    }

    static ConquestData create(final String planeName, final String name) {
        if (currentBefore == null) {
            currentBefore = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        }
        final ConquestPlane plane = FModel.getPlanes().get(planeName);
        final PaperCard commander = plane.getCommanders().get(0);
        final PaperCard planeswalker = ConquestUtil.getStartingPlaneswalkerOptions(commander).iterator().next();
        final ConquestData data = FModel.getConquest().create(name, plane, planeswalker, commander);
        made.add(data);
        return data;
    }

    /** The first place on a plane whose event opens a secret plane, or null. */
    static ConquestLocation portal(final ConquestPlane plane) {
        for (int region = 0; region < plane.getRegions().size(); region++) {
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
                    if (loc.getEvent().getTemporaryUnlock() != null) {
                        return loc;
                    }
                }
            }
        }
        return null;
    }

    static void cleanUp() {
        FModel.getConquest().cancelBattle();
        FModel.getConquest().setModel(null);
        for (final ConquestData data : made) {
            ConquestController.delete(data);
        }
        made.clear();
        // The portal flag is shared by the whole process
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (List.of("Time_Vault", "Unstable_Realm").contains(plane.getName())) {
                plane.setTemporarilyReachable(false);
            }
        }
        if (currentBefore != null) {
            final ConquestPreferences prefs = FModel.getConquestPreferences();
            prefs.setPref(CQPref.CURRENT_CONQUEST, currentBefore);
            prefs.save();
            currentBefore = null;
        }
    }
}
