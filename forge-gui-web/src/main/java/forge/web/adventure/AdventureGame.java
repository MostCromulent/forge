package forge.web.adventure;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.adventure.world.WorldSaveHeader;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.game.GameView;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.web.campaign.Campaign;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.AdventureCreate;
import forge.web.protocol.CampaignProtocol.AdventureDialogChoice;
import forge.web.protocol.CampaignProtocol.AdventureEncounter;
import forge.web.protocol.CampaignProtocol.AdventureEnemy;
import forge.web.protocol.CampaignProtocol.AdventureEnterPoi;
import forge.web.protocol.CampaignProtocol.AdventureHud;
import forge.web.protocol.CampaignProtocol.AdventureMove;
import forge.web.protocol.CampaignProtocol.AdventurePlaceholder;
import forge.web.protocol.CampaignProtocol.AdventurePlaceholderDone;
import forge.web.protocol.CampaignProtocol.AdventurePoi;
import forge.web.protocol.CampaignProtocol.AdventureQuickSave;
import forge.web.protocol.CampaignProtocol.AdventureSave;
import forge.web.protocol.CampaignProtocol.AdventureSaveTo;
import forge.web.protocol.CampaignProtocol.AdventureSaves;
import forge.web.protocol.CampaignProtocol.AdventureState;
import forge.web.protocol.CampaignProtocol.AdventureTick;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.CampaignProtocol.CampaignPrefs;
import forge.web.protocol.CampaignProtocol.CampaignResult;
import forge.web.protocol.CampaignProtocol.CampaignStats;
import forge.web.protocol.CampaignProtocol.Reward;
import forge.web.protocol.CampaignProtocol.TradePick;
import forge.web.protocol.CampaignProtocol.Trading;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.ToBrowser.CataloguePage;
import forge.web.protocol.ToBrowser.Notice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * Adventure as a campaign mode. The saves are the mobile client's, in the same folder, named by file. Every call into the adventure
 * model runs on Forge's GUI thread, because the model loads its pictures through Assets. The world map runs on that thread too: each
 * move the browser reports ticks it once, so a save always sees the map between two ticks.
 */
public final class AdventureGame implements Campaign, IHasForgeLog {
    /** The longest step one move may advance the map by, so a stalled browser does not jump the enemies forward. */
    private static final float LONGEST_TICK = 0.25f;
    /** How far above the spawn town a new player is put, as WorldStage.enter puts it, so leaving it does not enter it again. */
    private static final float ABOVE_SPAWN = 18f;

    private final Host host;
    private final WebAdventureHost screens = new WebAdventureHost();
    private final Dialogs dialogs;
    private Overworld overworld;
    private long lastTick;
    /** The point of interest whose map the browser stands in for with a notice, or null. */
    private PointOfInterest placeholder;
    private boolean leavingSpawn;
    private EnemySprite encountered;

    public AdventureGame(final Host host) {
        this.host = host;
        dialogs = new Dialogs(host::tell);
        AdventureRuntime.start();
        screens.onQuestDialogs(dialogs::collect);
        AdventureHost.set(screens);
    }

    @Override
    public AdventureSaves saves() {
        final List<AdventureSave> rows = new ArrayList<>();
        final File[] files = new File(WorldSave.getSaveDir()).listFiles((dir, name) -> WorldSave.isSafeFile(name));
        if (files != null) {
            for (final File file : files) {
                final String name = headerName(file);
                if (name != null) {
                    rows.add(new AdventureSave(file.getName(), WorldSave.filenameToSlot(file.getName()), name, Campaign.savedOn(file)));
                }
            }
        }
        rows.sort(Comparator.comparingInt(AdventureSave::slot));
        return new AdventureSaves(rows, current());
    }

    /** The name the save was given, or null when the file cannot be read. */
    private static String headerName(final File file) {
        try (FileInputStream in = new FileInputStream(file);
             InflaterInputStream inflater = new InflaterInputStream(in);
             ObjectInputStream objects = new ObjectInputStream(inflater)) {
            final WorldSaveHeader header = (WorldSaveHeader) objects.readObject();
            header.dispose();
            return header.name;
        } catch (final IOException | ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }

    @Override
    public String current() {
        final String last = Config.instance().getSettingData().lastActiveSave;
        return last != null && WorldSave.isSafeFile(last) && new File(WorldSave.getSaveDir(), last).isFile() ? last : null;
    }

    @Override
    public String open(final String save) {
        if (save == null || !WorldSave.isSafeFile(save) || !Campaign.isSaveNamed(WorldSave.getSaveDir(), new File(WorldSave.getSaveDir(), save), save)) {
            return null;
        }
        return AdventureRuntime.onUi(() -> {
            if (!WorldSave.load(WorldSave.filenameToSlot(save))) {
                return null;
            }
            onWorldMap(null, false);
            return save;
        });
    }

    /** A world was loaded or made: the map starts over on it, standing in point, or on the world map when point is null. */
    private void onWorldMap(final PointOfInterest point, final boolean spawn) {
        WorldImages.reset();
        overworld = new Overworld(Current.world(), MyRandom.getRandom(), new Overworld.Listener() {
            @Override
            public void spawned(final AdventureEnemy enemy) {
            }

            @Override
            public void despawned(final int id) {
            }

            @Override
            public void encounter(final int id, final EnemySprite mob) {
                encountered = mob;
                host.tell(new AdventureEncounter(id, mob.getAtlasPath(), mob.getName()));
            }
        });
        screens.overworld(overworld);
        lastTick = 0;
        encountered = null;
        enter(point, spawn);
    }

    private void enter(final PointOfInterest point, final boolean spawn) {
        placeholder = point;
        leavingSpawn = spawn;
        screens.map(point);
        overworld.freeze(point != null);
    }

    /** A save's name is its slot's, so only the text shown in it could change. The page does not offer this. */
    @Override
    public String rename(final String name, final String to) {
        return Localizer.getInstance().getMessage("lblWebAdventureNoRename");
    }

    @Override
    public void delete(final String name) {
        final File file = new File(WorldSave.getSaveDir(), name);
        if (WorldSave.isSafeFile(name) && Campaign.isSaveNamed(WorldSave.getSaveDir(), file, name) && !file.delete()) {
            netLog.warn("Could not delete the adventure save {}", file);
        }
    }

    @Override
    public List<Record> page() {
        return AdventureRuntime.onUi(() -> {
            final List<Record> page = new ArrayList<>(List.of(bar(), state()));
            if (placeholder != null) {
                page.add(new AdventurePlaceholder(placeholder.getDisplayName()));
            }
            if (dialogs.isOpen()) {
                page.add(dialogs.current());
            }
            return page;
        });
    }

    private CampaignBar bar() {
        return new CampaignBar(AdventurePlayer.current().getName(), Config.instance().getPlane(), List.of(), Map.of());
    }

    private AdventureState state() {
        final World world = Current.world();
        final AdventurePlayer player = AdventurePlayer.current();
        final List<AdventurePoi> pois = new ArrayList<>();
        for (final PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null) {
                continue;
            }
            final PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID());
            pois.add(new AdventurePoi(poi.getID(), poi.getDisplayName(), poi.getPosition().x, poi.getPosition().y, poi.getSprite().getRegionWidth(),
                    poi.getSprite().getRegionHeight(), poi.getData().spriteAtlas, poi.getSprite().getRegionX(), poi.getSprite().getRegionY(),
                    poi.getActive(), changes.isVisited(), changes.isBookmarked(), poi.getData().isDungeon()));
        }
        return new AdventureState(WorldImages.key(), world.getWidthInTiles(), world.getHeightInTiles(), world.getTileSize(), world.getChunkSize(),
                player.getWorldPosX(), player.getWorldPosY(), player.spriteName(), overworld.playerSpeed(), pois, overworld.enemies(), hud());
    }

    private AdventureHud hud() {
        final AdventurePlayer player = AdventurePlayer.current();
        return new AdventureHud(player.getLife(), player.getMaxLife(), player.getGold(), player.getShards(), navAngle(),
                screens.takeNotifications(), screens.takeAnnouncement());
    }

    // Ported from WorldStage.drawNavigationArrow, without its repair of an orphaned hunt quest
    private Float navAngle() {
        final Vector2 from = overworld.playerCenter();
        for (final AdventureQuestData quest : AdventurePlayer.current().getQuests()) {
            if (!quest.isTracked) {
                continue;
            }
            final PointOfInterest poi = quest.getClosestValidPOI(from);
            if (poi != null) {
                return poi.getCenter().sub(from).angleDeg();
            }
            final EnemySprite target = quest.getTargetEnemySprite();
            if (target != null && overworld.has(target)) {
                return target.getCenter().sub(from).angleDeg();
            }
            return null;
        }
        return null;
    }

    private AdventureTick move(final AdventureMove m) {
        final long now = System.nanoTime();
        final float delta = lastTick == 0 ? 0 : Math.min(LONGEST_TICK, (now - lastTick) / 1e9f);
        lastTick = now;
        // WorldStage.onActing does nothing while a dialog takes the input
        if (dialogs.isOpen()) {
            return new AdventureTick(overworld.enemies(), overworld.playerSpeed(), hud());
        }
        if (!overworld.frozen()) {
            AdventurePlayer.current().setWorldPosX(m.x());
            AdventurePlayer.current().setWorldPosY(m.y());
            overworld.player(m.x(), m.y(), m.moving());
        }
        overworld.tick(delta);
        return new AdventureTick(overworld.enemies(), overworld.playerSpeed(), hud());
    }

    // Ported from WorldStage.handlePointsOfInterestCollision and TileMapScene.load
    private boolean enterPoi(final String id) {
        if (overworld.frozen()) {
            return false;
        }
        final PointOfInterest poi = Current.world().getAllPointOfInterest().stream().filter(p -> p != null && p.getID().equals(id)).findFirst().orElse(null);
        if (poi == null || !poi.getActive() || !overworld.touches(poi.getBoundingRectangle())) {
            return false;
        }
        overworld.enteredPoi();
        WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).visit();
        enter(poi, false);
        AdventureQuestController.instance().updateEnteredPOI(poi);
        AdventureHost.get().showQuestDialogs();
        WorldSave.getCurrentSave().autoSave();
        return true;
    }

    private void leavePlaceholder() {
        if (placeholder == null) {
            return;
        }
        if (leavingSpawn) {
            AdventurePlayer.current().setWorldPosX(placeholder.getPosition().x);
            AdventurePlayer.current().setWorldPosY(placeholder.getPosition().y + ABOVE_SPAWN);
        }
        overworld.player(AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getWorldPosY(), false);
        enter(null, false);
    }

    @Override
    public Reward reward() {
        return null;
    }

    @Override
    public void claim(final Host host) {
    }

    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        return null;
    }

    @Override
    public CampaignResult result() {
        return null;
    }

    @Override
    public void left() {
    }

    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        return new CataloguePage(q.request(), List.of(), 0, 0, 0, false, q.source());
    }

    @Override
    public Trading trading() {
        return new Trading(List.of(), "", null, null, null);
    }

    @Override
    public String trade(final String source, final List<TradePick> picks) {
        return null;
    }

    @Override
    public CampaignStats stats(final String scope) {
        return new CampaignStats("", List.of(), List.of(), List.of(), null, null);
    }

    @Override
    public CampaignPrefs prefs(final String problem) {
        return new CampaignPrefs("", List.of(), "", problem, false);
    }

    @Override
    public String setPref(final String key, final String value) {
        return null;
    }

    @Override
    public void resetPrefs() {
    }

    // Copied from NewGameScene.start
    private void create(final AdventureCreate c) {
        final DifficultyData[] difficulties = Config.instance().getConfigData().difficulties;
        final DifficultyData difficulty = difficulties[Math.max(0, Math.min(c.difficulty(), difficulties.length - 1))];
        final CardEdition edition = c.edition() == null ? null : FModel.getMagicDb().getEditions().get(c.edition());
        WorldSave.generateNewWorld(c.name(), !c.female(), c.race(), c.avatar(), ColorSet.fromNames(c.colors().toCharArray()), difficulty,
                AdventureModes.valueOf(c.mode()), c.customDeck(), edition, c.seed());
        GamePlayerUtil.getGuiPlayer().setName(c.name());
        if (AdventurePlayer.current().getQuests().stream().noneMatch(q -> q.getID() == 28)) {
            AdventurePlayer.current().addQuest("28", true);
        }
        final PointOfInterest spawn = Current.world().findPointsOfInterest("Spawn");
        onWorldMap(spawn, true);
        // As TileMapScene.load does for the spawn town that WorldStage.enterSpawnPOI opens
        AdventureQuestController.instance().updateEnteredPOI(spawn);
        AdventureHost.get().showQuestDialogs();
        WorldSave.getCurrentSave().autoSave();
    }

    private static boolean saveTo(final int slot) {
        return AdventureRuntime.onUi(() -> WorldSave.getCurrentSave().save(AdventurePlayer.current().getName() + AdventureHost.get().saveFileSuffix(), slot));
    }

    private static void reportSave(final BrowserChannel channel, final Host host, final boolean saved) {
        final Localizer text = Localizer.getInstance();
        channel.send(saved ? new Notice(null, text.getMessage("lblWebAdventureSaved"), false) : host.error(text.getMessage("lblWebAdventureSaveFailed")));
    }

    private record Call(AdventureGame game, String save, Host host) {
    }

    private static <T extends Record> Handler<Call, T> onShelf(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() == null) {
                handler.run(c, ch, command);
            }
        };
    }

    /** Runs only while a save is open and its world map has been made. */
    private static <T extends Record> Handler<Call, T> inSave(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() != null && c.game().overworld != null) {
                handler.run(c, ch, command);
            }
        };
    }

    public static final Commands<Call> COMMANDS = new Commands<Call>()
            .on(AdventureCreate.class, onShelf((c, ch, create) -> {
                try {
                    AdventureRuntime.onUi(() -> {
                        c.game().create(create);
                        return null;
                    });
                } catch (final IllegalArgumentException e) {
                    ch.send(c.host().error(e.getMessage()));
                    return;
                }
                c.host().opened(WorldSave.filename(WorldSave.AUTO_SAVE_SLOT));
            }))
            .on(AdventureSaveTo.class, inSave((c, ch, s) -> reportSave(ch, c.host(), s.slot() >= 0 && saveTo(s.slot()))))
            .on(AdventureQuickSave.class, inSave((c, ch, s) -> reportSave(ch, c.host(), saveTo(WorldSave.QUICK_SAVE_SLOT))))
            .on(AdventureMove.class, inSave((c, ch, m) -> ch.send(AdventureRuntime.onUi(() -> c.game().move(m)))))
            .on(AdventureEnterPoi.class, inSave((c, ch, e) -> {
                final String name = AdventureRuntime.onUi(() -> c.game().enterPoi(e.id()) ? c.game().placeholder.getDisplayName() : null);
                if (name != null) {
                    ch.send(new AdventurePlaceholder(name));
                }
            }))
            .on(AdventureDialogChoice.class, inSave((c, ch, d) -> AdventureRuntime.onUi(() -> {
                c.game().dialogs.choose(d.dialogId(), d.index());
                return null;
            })))
            .on(AdventurePlaceholderDone.class, inSave((c, ch, d) -> ch.send(AdventureRuntime.onUi(() -> {
                c.game().leavePlaceholder();
                return c.game().state();
            }))));

    @Override
    public void handle(final BrowserChannel channel, final Record command, final String save, final Host host) {
        COMMANDS.run(new Call(this, save, host), channel, command);
    }
}
