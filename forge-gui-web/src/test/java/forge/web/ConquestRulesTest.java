package forge.web;

import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Conquest's rules as the shared package states them, with no screen involved. */
public class ConquestRulesTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        ConquestFixture.cleanUp();
    }

    // Fails if a second conquest can take the name of one that exists, which would write into the first one's folder
    @Test
    public void aNameInUseIsRefused() {
        final String name = ConquestFixture.name();
        assertNull(ConquestUtil.nameProblem(name));
        ConquestFixture.create("Zendikar", name);
        assertNotNull(ConquestUtil.nameProblem(name));
        assertNotNull(ConquestUtil.nameProblem("  "));
    }

    // Fails if a created conquest is not on disk as created: missing from the list, or read back with another
    // place, balance or commander deck
    @Test
    public void aCreatedConquestIsListedAndReadsBack() {
        final ConquestData made = ConquestFixture.create("Zendikar");
        assertSame(FModel.getConquest().getModel(), made);
        final ConquestData read = ConquestController.listSaves().stream()
                .filter(d -> d.getName().equals(made.getName())).findFirst().orElse(null);
        assertNotNull(read, "the new conquest is not listed");
        assertEquals(read.getCurrentPlane().getName(), "Zendikar");
        assertEquals(read.getAEtherShards(), made.getAEtherShards());
        assertEquals(read.getUnlockedCardCount(), made.getUnlockedCardCount());
        final int deckSize = made.getSelectedCommander().getDeck().getMain().countAll();
        FModel.getConquest().load(read);
        assertEquals(read.getSelectedCommander().getDeck().getMain().countAll(), deckSize);
        assertTrue(deckSize >= 40);
    }

    // Fails if deleting leaves the save listed
    @Test
    public void aDeletedConquestIsGone() {
        final ConquestData made = ConquestFixture.create("Zendikar");
        ConquestController.delete(made);
        assertFalse(ConquestController.listSaves().stream().anyMatch(d -> d.getName().equals(made.getName())));
    }
}
