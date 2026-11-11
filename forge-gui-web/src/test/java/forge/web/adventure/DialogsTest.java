package forge.web.adventure;

import forge.adventure.data.DialogData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureHost;
import forge.web.protocol.CampaignProtocol.AdventureChoice;
import forge.web.protocol.CampaignProtocol.AdventureDialog;
import forge.web.protocol.CampaignProtocol.AdventureDialogClosed;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class DialogsTest {
    private final List<Record> sent = new ArrayList<>();
    private Dialogs dialogs;
    private WebAdventureHost screens;

    @BeforeMethod
    public void setUp() {
        AdventureFixture.newWorld(42);
        sent.clear();
        dialogs = new Dialogs(sent::add);
        screens = new WebAdventureHost();
        screens.onQuestDialogs(dialogs::collect);
        AdventureHost.set(screens);
    }

    @AfterMethod
    public void tearDown() {
        AdventureHost.set(new WebAdventureHost());
    }

    private AdventureDialog last() {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i) instanceof AdventureDialog d) {
                return d;
            }
        }
        return null;
    }

    private static DialogData option(final String name) {
        final DialogData d = new DialogData();
        d.name = name;
        return d;
    }

    private static DialogData.ActionData action() {
        return new DialogData.ActionData();
    }

    // Fails if the main quest's prologue, queued by AdventureQuestController.showQuestDialogs, never reaches the browser
    @Test
    public void quest28PrologueReachesTheBrowser() {
        AdventureRuntime.onUi(() -> {
            AdventurePlayer.current().addQuest("28", false);
            return null;
        });
        final AdventureDialog dialog = last();
        assertNotNull(dialog, sent.toString());
        assertFalse(dialog.text().isEmpty());
        assertTrue(dialogs.isOpen());
    }

    // Fails if an option whose condition fails is offered, as MapDialog.loadDialog leaves it out
    @Test
    public void falseConditionHidesChoice() {
        final DialogData rich = option("Rich");
        final DialogData.ConditionData gold = new DialogData.ConditionData();
        gold.hasGold = 999_999_999;
        rich.condition = new DialogData.ConditionData[] {gold};
        final DialogData d = new DialogData();
        d.text = "Hello";
        d.options = new DialogData[] {rich, option("Poor")};
        AdventureRuntime.onUi(() -> {
            dialogs.open(d);
            return null;
        });
        final List<AdventureChoice> choices = last().choices();
        assertEquals(choices.size(), 1);
        assertEquals(choices.get(0).label(), "Poor");
        assertEquals(choices.get(0).index(), 1);
    }

    // Fails if choosing an option does not run its actions, as MapDialog.setEffects does
    @Test
    public void addGoldAction() {
        final DialogData take = option("Take");
        final DialogData.ActionData gold = action();
        gold.addGold = 50;
        take.action = new DialogData.ActionData[] {gold};
        final DialogData d = new DialogData();
        d.text = "A purse";
        d.options = new DialogData[] {take};
        final int before = AdventureRuntime.onUi(() -> AdventurePlayer.current().getGold());
        AdventureRuntime.onUi(() -> {
            dialogs.open(d);
            dialogs.choose(last().id(), 0);
            return null;
        });
        assertEquals((int) AdventureRuntime.onUi(() -> AdventurePlayer.current().getGold()), before + 50);
        assertFalse(dialogs.isOpen());
        assertTrue(sent.get(sent.size() - 1) instanceof AdventureDialogClosed);
    }

    @Test
    public void issueQuestAction() {
        final DialogData accept = option("Accept");
        final DialogData.ActionData quest = action();
        quest.issueQuest = "2";
        accept.action = new DialogData.ActionData[] {quest};
        final DialogData d = new DialogData();
        d.text = "Wander?";
        d.options = new DialogData[] {accept};
        AdventureRuntime.onUi(() -> {
            dialogs.open(d);
            dialogs.choose(last().id(), 0);
            return null;
        });
        assertTrue(AdventureRuntime.onUi(() -> AdventurePlayer.current().getQuests().stream().anyMatch(q -> q.getID() == 2)));
    }

    // Fails if an option with options of its own does not show them next, as a nested MapDialog.loadDialog does
    @Test
    public void nestedOptionsFollow() {
        final DialogData more = option("More");
        more.text = "Second page";
        more.options = new DialogData[] {option("Bye")};
        final DialogData d = new DialogData();
        d.text = "First page";
        d.options = new DialogData[] {more};
        AdventureRuntime.onUi(() -> {
            dialogs.open(d);
            dialogs.choose(last().id(), 0);
            return null;
        });
        assertEquals(last().text(), "Second page");
        assertEquals(last().choices().get(0).label(), "Bye");
        assertTrue(dialogs.isOpen());
    }

    @Test
    public void staleChoiceIsIgnored() {
        final DialogData d = new DialogData();
        d.text = "Hello";
        d.options = new DialogData[] {option("Bye")};
        AdventureRuntime.onUi(() -> {
            dialogs.open(d);
            dialogs.choose(last().id() + 1, 0);
            return null;
        });
        assertTrue(dialogs.isOpen());
    }
}
