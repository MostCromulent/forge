package forge.web.adventure;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.BiomeData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.Scene;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.web.protocol.CampaignProtocol.AdventureEnemy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The world map's rules, ported from the mobile WorldStage and GameStage: monster spawns, the chase, despawning and encounters.
 * The browser moves the player and reports where it is. Every call runs on Forge's GUI thread, as the stages run on the GL thread.
 */
final class Overworld {
    interface Listener {
        void spawned(AdventureEnemy enemy);

        void despawned(int id);

        /** id is the one this map gave the enemy, which the browser knows it by. */
        void encounter(int id, EnemySprite mob);
    }

    enum Modification { Fly, Hide, Sprint }

    private static final float SPAWN_INTERVAL = 4;
    private static final Listener SILENT = new Listener() {
        @Override
        public void spawned(final AdventureEnemy enemy) {
        }

        @Override
        public void despawned(final int id) {
        }

        @Override
        public void encounter(final int id, final EnemySprite mob) {
        }
    };

    private final World world;
    private final Random random;
    private final Listener listener;
    /** Each enemy with the global time it spawned at. */
    private final List<Spawned> enemies = new ArrayList<>();
    private final Map<Modification, Float> modifications = new EnumMap<>(Modification.class);
    /** The player's body, for its size and bounding box. */
    private final CharacterSprite player;
    private final Rectangle box = new Rectangle();
    private final Vector2 move = new Vector2();
    private boolean moving;
    private int nextId = 1;
    private float spawnDelay;
    private float globalTimer;
    private float moveModifier = 1;
    private boolean frozen;
    private boolean poiThisTick;

    /** at is the global time the enemy spawned at. id names it to the browser, because enemies made from EnemyData all have object id 0. */
    private record Spawned(int id, float at, EnemySprite sprite) {
    }

    Overworld(final World world, final Random random, final Listener listener) {
        this.world = world;
        this.random = random;
        this.listener = listener == null ? SILENT : listener;
        player = new CharacterSprite(AdventurePlayer.current().spriteName());
        player.setPosition(AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getWorldPosY());
    }

    void player(final float x, final float y, final boolean moving) {
        player.setPosition(x, y);
        this.moving = moving;
    }

    float playerX() {
        return player.getX();
    }

    float playerY() {
        return player.getY();
    }

    /** Whether the player's box overlaps the rectangle, as PlayerSprite.collideWith tests a point of interest. */
    boolean touches(final Rectangle rectangle) {
        return player.collideWith(rectangle);
    }

    Vector2 playerCenter() {
        return player.getCenter();
    }

    boolean has(final EnemySprite mob) {
        return enemies.stream().anyMatch(e -> e.sprite() == mob);
    }

    boolean frozen() {
        return frozen;
    }

    /** Encounters, dialogs and point-of-interest maps stop the world map until they end. */
    void freeze(final boolean frozen) {
        this.frozen = frozen;
    }

    /** The player walked into a point of interest. Mobile checks those before enemies, so no battle starts on the same tick. */
    void enteredPoi() {
        poiThisTick = true;
        frozen = true;
    }

    void modify(final String kind, final float seconds) {
        modifications.put(Modification.valueOf(kind), seconds);
    }

    boolean modified(final Modification kind) {
        return modifications.containsKey(kind);
    }

    /** The player's walking speed in pixels per second, as PlayerSprite.act computes it. */
    float playerSpeed() {
        return Config.instance().getConfigData().playerBaseSpeed * moveModifier * AdventurePlayer.current().equipmentSpeed();
    }

    // Ported from GameStage.act and WorldStage.onActing
    void tick(final float delta) {
        modifications.replaceAll((kind, left) -> left - delta);
        modifications.values().removeIf(left -> left < 0);
        if (frozen) {
            poiThisTick = false;
            return;
        }
        if (!moving) {
            return;
        }
        handleMonsterSpawn(delta);
        globalTimer += delta;
        final boolean collided = poiThisTick;
        poiThisTick = false;
        for (int i = 0; i < enemies.size(); i++) {
            final Spawned pair = enemies.get(i);
            final EnemySprite mob = pair.sprite();
            if (globalTimer >= pair.at() + mob.getLifetime()) {
                AdventureQuestController.instance().updateDespawn(mob);
                AdventureHost.get().showQuestDialogs();
                enemies.remove(i);
                i--;
                listener.despawned(pair.id());
                continue;
            }
            if (!modifications.containsKey(Modification.Hide)) {
                chase(mob, delta);
            }
            if (!collided && player.collideWith(mob)) {
                frozen = true;
                listener.encounter(pair.id(), mob);
                break;
            }
        }
    }

    private void chase(final EnemySprite mob, final float delta) {
        move.set(player.getX(), player.getY()).sub(mob.pos());
        move.setLength(mob.speed() * delta);
        box.set(mob.getX() + move.x, mob.getY() + move.y, mob.getWidth(), mob.getHeight() * mob.getCollisionHeight());
        if (!mob.getData().flying && world.collidingTile(box)) {
            box.set(mob.getX() + move.x, mob.getY(), mob.getWidth(), mob.getHeight());
            if (world.collidingTile(box)) {
                box.set(mob.getX(), mob.getY() + move.y, mob.getWidth(), mob.getHeight());
                if (!world.collidingTile(box)) {
                    mob.moveBy(0, move.y);
                }
            } else {
                mob.moveBy(move.x, 0);
            }
        } else {
            mob.moveBy(move.x, move.y);
        }
    }

    // Ported from WorldStage.handleMonsterSpawn
    private void handleMonsterSpawn(final float delta) {
        for (final EnemySprite quest : AdventureQuestController.instance().getQuestSprites()) {
            if (enemies.stream().noneMatch(e -> e.sprite() == quest)) {
                spawn(quest, 2.5f);
            }
        }
        final int biome = World.highestBiome(world.getBiome((int) ((player.getX() + player.getWidth() / 2f) / world.getTileSize()),
                (int) (player.getY() / world.getTileSize())));
        final List<BiomeData> biomes = world.getData().GetBiomes();
        final float sprint = modifications.containsKey(Modification.Sprint) ? 2 : 1;
        if (biomes.size() <= biome) {
            moveModifier = 1.5f * sprint;
            return;
        }
        moveModifier = sprint;
        final BiomeData data = biomes.get(biome);
        if (data == null) {
            return;
        }
        spawnDelay -= delta;
        if (spawnDelay >= 0) {
            return;
        }
        spawnDelay = SPAWN_INTERVAL + random.nextFloat() * 4.0f;
        if (data.getEnemyList() == null) {
            return;
        }
        final EnemyData enemy = data.getEnemy(1.0f);
        final EnemyData extra = data.getExtraSpawnEnemy(1.0f);
        if (extra != null) {
            final float picker = random.nextFloat();
            if (picker > 0.5f) {
                spawn(enemy);
                spawn(extra);
            } else if (picker > 0.2f) {
                spawn(extra);
            } else {
                spawn(enemy);
            }
        } else {
            spawn(enemy);
        }
    }

    private void spawn(final EnemyData data) {
        if (data != null) {
            spawn(new EnemySprite(data), 1f);
        }
    }

    // Ported from WorldStage.spawn and spawnQuestSprite
    private boolean spawn(final EnemySprite sprite, final float distance) {
        final float unit = Scene.getIntendedHeight() / 6f;
        final Vector2 at = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            at.setLength((unit + unit * 3 * random.nextFloat()) * distance);
            at.setAngleDeg(360 * random.nextFloat());
            for (int i = 0; i < 10; i++) {
                final boolean right = sprite.getX() > player.getX();
                final boolean above = sprite.getY() > player.getY();
                sprite.setX(player.getX() + at.x + i * sprite.getWidth() * (right ? 1 : -1));
                sprite.setY(player.getY() + at.y + i * sprite.getHeight() * (above ? 1 : -1));
                if (sprite.getData().flying || !world.collidingTile(sprite.boundingRect())) {
                    add(globalTimer, sprite);
                    return true;
                }
            }
        }
        return false;
    }

    /** Puts an enemy at a place on the map, as a console command or the dev menu would. */
    AdventureEnemy place(final EnemyData data, final float x, final float y) {
        final EnemySprite sprite = new EnemySprite(data);
        sprite.setPosition(x, y);
        return add(globalTimer, sprite);
    }

    private AdventureEnemy add(final float at, final EnemySprite sprite) {
        final Spawned spawned = new Spawned(nextId++, at, sprite);
        enemies.add(spawned);
        final AdventureEnemy view = view(spawned);
        listener.spawned(view);
        return view;
    }

    /** Takes the enemy off the map after its battle. */
    void remove(final EnemySprite mob) {
        for (final Spawned e : enemies) {
            if (e.sprite() == mob) {
                enemies.remove(e);
                listener.despawned(e.id());
                return;
            }
        }
    }

    List<AdventureEnemy> enemies() {
        final List<AdventureEnemy> views = new ArrayList<>(enemies.size());
        for (final Spawned e : enemies) {
            views.add(view(e));
        }
        return views;
    }

    private AdventureEnemy view(final Spawned e) {
        final EnemySprite sprite = e.sprite();
        final String animation = moving && !modifications.containsKey(Modification.Hide) && sprite.speed() > 0 ? "Walk" : "Idle";
        return new AdventureEnemy(e.id(), sprite.getAtlasPath(), sprite.getX(), sprite.getY(), animation, sprite.getData().flying,
                sprite.getData().boss);
    }

    // Ported from WorldStage.save
    SaveFileData save() {
        final ArrayList<Float> timeouts = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<Float> xs = new ArrayList<>();
        final ArrayList<Float> ys = new ArrayList<>();
        final ArrayList<String> questStageIds = new ArrayList<>();
        for (final Spawned e : enemies) {
            timeouts.add(e.at());
            names.add(e.sprite().getData().getName());
            xs.add(e.sprite().getX());
            ys.add(e.sprite().getY());
            questStageIds.add(e.sprite().questStageID);
        }
        final SaveFileData data = new SaveFileData();
        data.storeObject("timeouts", timeouts);
        data.storeObject("names", names);
        data.storeObject("x", xs);
        data.storeObject("y", ys);
        data.storeObject("questStageIDs", questStageIds);
        data.store("globalTimer", globalTimer);
        return data;
    }

    // Ported from WorldStage.load
    @SuppressWarnings("unchecked")
    void load(final SaveFileData data) {
        enemies.clear();
        try {
            final List<Float> timeouts = (List<Float>) data.readObject("timeouts");
            final List<String> names = (List<String>) data.readObject("names");
            final List<Float> xs = (List<Float>) data.readObject("x");
            final List<Float> ys = (List<Float>) data.readObject("y");
            final List<String> questStageIds = (List<String>) data.readObject("questStageIDs");
            for (int i = 0; i < timeouts.size(); i++) {
                final EnemySprite sprite = new EnemySprite(WorldData.getEnemy(names.get(i)));
                sprite.setX(xs.get(i));
                sprite.setY(ys.get(i));
                sprite.questStageID = questStageIds.get(i);
                if (sprite.questStageID != null) {
                    AdventureQuestController.instance().rematchQuestSprite(sprite);
                }
                enemies.add(new Spawned(nextId++, timeouts.get(i), sprite));
            }
            globalTimer = data.readFloat("globalTimer");
        } catch (final Exception e) {
            // WorldStage.load also starts with an empty map when the save has none
        }
    }
}
