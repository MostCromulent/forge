package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.adventure.data.BiomeData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.adventure.AdventureFixture;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Tests adventure driven from a browser. */
public class AdventureSessionTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @BeforeClass
    public void boot() {
        AdventureFixture.boot();
    }

    /** Connects the host and opens the list of adventure saves. */
    TestBrowser onTheShelf() throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        send(host, message("campaignOpen", "mode", "adventure", "resume", false));
        host.awaitMatching("hello", h -> "adventure".equals(str(h, "campaign")) && str(h, "campaignSave") == null, "the shelf did not open");
        host.awaitMatching("adventureSaves", s -> true, "the saves were not listed");
        return host;
    }

    TestBrowser inNewGame() throws InterruptedException {
        final TestBrowser host = onTheShelf();
        send(host, message("adventureCreate", "name", "Tester", "female", false, "race", 0, "avatar", 0, "colors", "W", "difficulty", 1,
                "mode", "Commander", "customDeck", 0, "seed", 42));
        host.awaitMatching("hello", h -> str(h, "campaignSave") != null, "the new game did not open");
        return host;
    }

    @AfterMethod(alwaysRun = true)
    public void leave() {
        for (final TestBrowser browser : browsers) {
            if (browser.hello != null && browser.hello.get("host").getAsBoolean()) {
                send(browser, message("campaignLeave"));
            }
        }
    }

    // Fails if a new game does not build the world, name the player and give the plane's main quest as NewGameScene does.
    // Quest 28 has no stages, so entering the spawn town completes it at once and only its dialogs remain to see
    @Test(timeOut = 120_000)
    public void newGameCreatesWorldAndGivesQuest28() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final AtomicReference<String> name = new AtomicReference<>();
        onUi(() -> name.set(AdventurePlayer.current().getName()));
        Assert.assertEquals(name.get(), "Tester");
        final JsonObject prologue = host.awaitMatching("adventureDialog", d -> true, "no prologue was shown");
        Assert.assertTrue(prologue.get("text").getAsString().startsWith("Disorientation"), prologue.toString());
    }

    // Fails if a save made in the browser cannot be opened again with the same player
    @Test(timeOut = 120_000)
    public void saveAndReopen() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final AtomicReference<float[]> before = new AtomicReference<>();
        onUi(() -> before.set(new float[] {AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getGold()}));
        send(host, message("adventureSaveTo", "slot", 3));
        host.awaitMatching("notice", n -> true, "saving was not confirmed");
        send(host, message("campaignLeave"));
        host.awaitMatching("adventureSaves", s -> s.getAsJsonArray("saves").toString().contains(WorldSave.filename(3)), "slot 3 was not listed");
        onUi(() -> AdventurePlayer.current().setWorldPosX(0));
        send(host, message("campaignLoad", "name", WorldSave.filename(3)));
        final JsonObject hello = host.awaitMatching("hello", h -> WorldSave.filename(3).equals(str(h, "campaignSave")), "slot 3 did not open");
        Assert.assertNotNull(hello);
        final AtomicReference<float[]> after = new AtomicReference<>();
        onUi(() -> after.set(new float[] {AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getGold()}));
        Assert.assertEquals(after.get(), before.get());
    }

    // Fails if opening a game does not send the world the overworld page draws
    @Test(timeOut = 120_000)
    public void openSendsState() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final JsonObject state = host.awaitMatching("adventureState", s -> true, "no world was sent");
        Assert.assertEquals(state.get("chunkSize").getAsInt(), 30);
        Assert.assertEquals(state.get("width").getAsInt(), 700);
        Assert.assertFalse(state.get("world").getAsString().isEmpty());
        Assert.assertTrue(state.getAsJsonArray("pois").size() > 10);
    }

    // Fails if a new game does not stop at its spawn town, as WorldStage.enterSpawnPOI does
    @Test(timeOut = 120_000)
    public void newGameStartsInSpawnPlaceholder() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        final AtomicReference<float[]> spawn = new AtomicReference<>();
        onUi(() -> {
            final PointOfInterest poi = Current.world().findPointsOfInterest("Spawn");
            spawn.set(new float[] {poi.getPosition().x, poi.getPosition().y + 18});
        });
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        final JsonObject state = host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        Assert.assertEquals(state.get("x").getAsFloat(), spawn.get()[0], 0.01);
        Assert.assertEquals(state.get("y").getAsFloat(), spawn.get()[1], 0.01);
    }

    // Fails if walking into a point of interest does not record the visit, save, and stop the map
    @Test(timeOut = 120_000)
    public void enterPoiMarksVisitedAndSaves() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final AtomicReference<PointOfInterest> town = new AtomicReference<>();
        onUi(() -> town.set(Current.world().getAllPointOfInterest().stream()
                .filter(p -> p.getActive() && !"Spawn".equals(p.getData().name)).findFirst().orElseThrow()));
        final File autoSave = new File(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT));
        autoSave.setLastModified(0);
        final float x = town.get().getPosition().x;
        final float y = town.get().getPosition().y;
        send(host, message("adventureMove", "x", x, "y", y, "moving", true));
        send(host, message("adventureEnterPoi", "id", town.get().getID()));
        host.awaitMatching("adventurePlaceholder", p -> true, "the town was not entered");
        final AtomicReference<Boolean> visited = new AtomicReference<>();
        onUi(() -> visited.set(WorldSave.getCurrentSave().getPointOfInterestChanges(town.get().getID()).isVisited()));
        Assert.assertTrue(visited.get());
        Assert.assertTrue(autoSave.lastModified() > 0);
    }

    // Fails if a point of interest far from where the player stands can be entered
    @Test(timeOut = 120_000)
    public void enterPoiNeedsTheTownUnderfoot() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final AtomicReference<PointOfInterest> town = new AtomicReference<>();
        onUi(() -> town.set(Current.world().getAllPointOfInterest().stream()
                .filter(p -> p.getActive() && !"Spawn".equals(p.getData().name)).findFirst().orElseThrow()));
        send(host, message("adventureMove", "x", town.get().getPosition().x + 2000, "y", town.get().getPosition().y, "moving", false));
        send(host, message("adventureEnterPoi", "id", town.get().getID()));
        Assert.assertNull(host.awaitMatching("adventurePlaceholder", p -> true));
    }

    // Fails if moves from the browser do not drive the world map and answer with its enemies
    @Test(timeOut = 120_000)
    public void movesTickTheMap() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final float[] ground = wildGround();
        final float x = ground[0];
        final float y = ground[1];
        JsonObject tick = null;
        for (int i = 0; i < 200 && (tick == null || tick.getAsJsonArray("enemies").isEmpty()); i++) {
            send(host, message("adventureMove", "x", x, "y", y, "moving", true));
            tick = host.awaitMatching("adventureTick", t -> true, "a move got no answer");
            Thread.sleep(20);
        }
        Assert.assertFalse(tick.getAsJsonArray("enemies").isEmpty(), "nothing spawned");
        Assert.assertTrue(tick.get("speed").getAsFloat() > 0);
    }

    // Fails if a reconnect loses the map or spawns its enemies again (Review Focus 2)
    @Test(timeOut = 120_000)
    public void reconnectResends() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final List<String> before = spawnUntilSome(host);
        final TestBrowser again = connect("host");
        final JsonObject state = again.awaitMatching("adventureState", s -> true, "a reconnect sent no world");
        Assert.assertEquals(ids(state.getAsJsonArray("enemies")), before);
    }

    // Fails if a save made while the map runs does not keep the enemies there at that moment (Review Focus 5)
    @Test(timeOut = 120_000)
    public void saveDuringTicks() throws InterruptedException {
        final TestBrowser host = inNewGame();
        host.awaitMatching("adventurePlaceholder", p -> true, "the spawn town was not entered");
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final int count = spawnUntilSome(host).size();
        send(host, message("adventureSaveTo", "slot", 4));
        host.awaitMatching("notice", n -> true, "saving was not confirmed");
        send(host, message("campaignLeave"));
        host.awaitMatching("adventureSaves", s -> true, "the saves were not listed");
        send(host, message("campaignLoad", "name", WorldSave.filename(4)));
        final JsonObject state = host.awaitMatching("adventureState", s -> true, "slot 4 sent no world");
        Assert.assertEquals(state.getAsJsonArray("enemies").size(), count);
    }

    // Fails if the plane's main quest does not open with its prologue, as the spawn town shows it on mobile
    @Test(timeOut = 120_000)
    public void newGameShowsTheQuestPrologue() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final JsonObject dialog = host.awaitMatching("adventureDialog", d -> true, "no prologue was shown");
        Assert.assertFalse(dialog.get("text").getAsString().isEmpty());
        Assert.assertFalse(dialog.getAsJsonArray("choices").isEmpty());
    }

    /** Puts a one-game enemy on the player and walks into it, then starts the battle once the encounter is shown. */
    private void fightGarth(final TestBrowser host) throws InterruptedException {
        dismissDialogs(host);
        send(host, message("adventurePlaceholderDone"));
        host.awaitMatching("adventureState", s -> true, "leaving the town sent no world");
        final float[] ground = wildGround();
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, true);
        send(host, message("adventureMove", "x", ground[0], "y", ground[1], "moving", false));
        host.awaitMatching("adventureTick", t -> true, "a stop got no answer");
        send(host, message("adventureDevSpawn", "enemy", "Garth"));
        JsonObject encounter = null;
        for (int i = 0; i < 20 && encounter == null; i++) {
            sessions.onMessage(host, message("adventureMove", "x", ground[0], "y", ground[1], "moving", true));
            encounter = host.awaitMatching("adventureEncounter", e -> true);
        }
        Assert.assertNotNull(encounter, "walking into the enemy started no encounter");
        send(host, message("adventureFight"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the battle did not start");
    }

    // Fails if conceding a battle does not end it as a loss that costs life, as AdventurePlayer.defeated does
    @Test(timeOut = 240_000)
    public void concededBattleCostsLife() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final AtomicReference<Integer> life = new AtomicReference<>();
        onUi(() -> life.set(AdventurePlayer.current().getLife()));
        fightGarth(host);
        awaitPriority(host);
        sessions.onMessage(host, TestBrowser.action("concede"));
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertFalse(result.get("won").getAsBoolean());
        send(host, TestBrowser.action("leave"));
        host.awaitMatching("adventureState", s -> true, "leaving the battle sent no world");
        final AtomicReference<Integer> after = new AtomicReference<>();
        onUi(() -> after.set(AdventurePlayer.current().getLife()));
        Assert.assertTrue(after.get() < life.get(), life.get() + " -> " + after.get());
    }

    // Fails if a won battle does not end with the enemy's loot on the reward page
    @Test(timeOut = 240_000)
    public void wonBattleShowsLoot() throws InterruptedException {
        final TestBrowser host = inNewGame();
        fightGarth(host);
        computerLoses(host);
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertTrue(result.get("won").getAsBoolean());
        send(host, TestBrowser.action("leave"));
        host.awaitMatching("reward", r -> !r.getAsJsonArray("steps").isEmpty(), "the loot was not shown");
    }

    // Fails if the inventory cannot be listed or a deck slot does not open in the web deck editor with the player's collection
    @Test(timeOut = 120_000)
    public void inventoryOpensDeckEditor() throws InterruptedException {
        final TestBrowser host = inNewGame();
        dismissDialogs(host);
        send(host, message("adventureInventoryQuery"));
        final JsonObject inventory = host.awaitMatching("adventureInventory", i -> true, "the inventory was not sent");
        Assert.assertFalse(inventory.getAsJsonArray("decks").isEmpty());
        send(host, message("adventureEditDeck", "slot", 0));
        final JsonObject editor = host.awaitMatching("editor", e -> e.has("state") && !e.get("state").isJsonNull(), "the deck editor did not open");
        Assert.assertEquals(editor.getAsJsonObject("state").get("collection").getAsString(), "Tester");
        sessions.onMessage(host, TestBrowser.action("editorClose"));
    }

    // Fails if the new-game form is not offered the plane's own modes: Realm of Legends has only commander starter decks
    @Test(timeOut = 120_000)
    public void optionsFollowThePlane() throws InterruptedException {
        final TestBrowser host = onTheShelf();
        send(host, message("adventureOptionsQuery"));
        final JsonObject options = host.awaitMatching("adventureOptions", o -> true, "no options were sent");
        final String modes = options.getAsJsonArray("modes").toString();
        Assert.assertTrue(modes.contains("\"Commander\""), modes);
        Assert.assertFalse(modes.contains("\"Standard\""), modes);
        Assert.assertTrue(options.getAsJsonArray("races").size() > 1);
        Assert.assertEquals(options.getAsJsonArray("difficulties").size(), 4);
    }

    /** Picks the first offered choice of each dialog until none is open. */
    private void dismissDialogs(final TestBrowser host) throws InterruptedException {
        host.awaitMatching("adventureDialog", d -> true, "no dialog was shown");
        for (int i = 0; i < 30; i++) {
            final JsonObject dialog = newest(host, "adventureDialog");
            final JsonObject closed = newest(host, "adventureDialogClosed");
            // JsonObject equality compares content, and every closing message looks the same, so positions are found from the end
            if (closed != null && host.got.lastIndexOf(closed) > host.got.lastIndexOf(dialog)) {
                return;
            }
            final JsonObject choice = dialog.getAsJsonArray("choices").get(0).getAsJsonObject();
            final int id = dialog.get("id").getAsInt();
            sessions.onMessage(host, message("adventureDialogChoice", "dialogId", id, "index", choice.get("index").getAsInt()));
            awaitTrue(() -> host.got.lastIndexOf(newest(host, "adventureDialog")) > host.got.lastIndexOf(dialog)
                    || newest(host, "adventureDialogClosed") != null && host.got.lastIndexOf(newest(host, "adventureDialogClosed")) > host.got.lastIndexOf(dialog),
                    "a choice got no answer");
        }
        Assert.fail("the dialogs did not end");
    }

    /** The newest message of a type received so far, without waiting, or null. */
    private static JsonObject newest(final TestBrowser host, final String type) {
        for (int i = host.got.size() - 1; i >= 0; i--) {
            if (type.equals(host.got.get(i).get("t").getAsString())) {
                return host.got.get(i);
            }
        }
        return null;
    }

    /** Walks in place on wild ground until at least one enemy is on the map, and returns the enemies' ids as the last answer lists them. */
    private List<String> spawnUntilSome(final TestBrowser host) throws InterruptedException {
        final float[] ground = wildGround();
        JsonObject tick = null;
        for (int i = 0; i < 200 && (tick == null || tick.getAsJsonArray("enemies").isEmpty()); i++) {
            send(host, message("adventureMove", "x", ground[0], "y", ground[1], "moving", true));
            tick = host.awaitMatching("adventureTick", t -> true, "a move got no answer");
            Thread.sleep(20);
        }
        send(host, message("adventureMove", "x", ground[0], "y", ground[1], "moving", false));
        tick = host.awaitMatching("adventureTick", t -> true, "a stop got no answer");
        Assert.assertFalse(tick.getAsJsonArray("enemies").isEmpty(), "nothing spawned");
        return ids(tick.getAsJsonArray("enemies"));
    }

    /** A place off the roads, where nothing blocks walking and the biome has enemies to spawn, in world pixels. */
    private static float[] wildGround() {
        final AtomicReference<float[]> found = new AtomicReference<>();
        onUi(() -> {
            final World world = Current.world();
            final List<BiomeData> biomes = world.getData().GetBiomes();
            for (int x = 30; x < world.getWidthInTiles() - 30 && found.get() == null; x += 3) {
                for (int y = 30; y < world.getHeightInTiles() - 30 && found.get() == null; y += 3) {
                    final int biome = World.highestBiome(world.getBiome(x, y));
                    if (biome < biomes.size() && biomes.get(biome).getEnemyList() != null && !world.isColliding(x, y)
                            && !world.isColliding(x + 1, y) && !world.isColliding(x, y + 1)) {
                        found.set(new float[] {x * world.getTileSize(), y * world.getTileSize()});
                    }
                }
            }
        });
        Assert.assertNotNull(found.get(), "no wild ground");
        return found.get();
    }

    private static List<String> ids(final JsonArray enemies) {
        final List<String> ids = new ArrayList<>();
        enemies.forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
        return ids;
    }
}
