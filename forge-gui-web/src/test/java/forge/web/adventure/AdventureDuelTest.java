package forge.web.adventure;

import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class AdventureDuelTest {
    private final List<EnemySprite> removed = new ArrayList<>();

    @BeforeMethod
    public void setUp() {
        AdventureFixture.newWorld(42);
        removed.clear();
    }

    /** A duel whose match has been prepared, as every real duel is before it ends. */
    private AdventureDuel duel(final EnemyData data) {
        return AdventureRuntime.onUi(() -> {
            final AdventureDuel duel = new AdventureDuel(new EnemySprite(data), removed::add);
            duel.prepare();
            return duel;
        });
    }

    private static EnemyData jodah(final int life) {
        final EnemyData data = new EnemyData(WorldData.getEnemy("Jodah"));
        data.life = life;
        data.boss = false;
        data.nextEnemy = null;
        return data;
    }

    // Fails if the opponent does not start at the enemy's life scaled by the difficulty, as DuelScene.enter sets it
    @Test
    public void preparedMatchUsesEnemyLife() {
        final AdventureDuel duel = duel(jodah(12));
        final PreparedMatch match = AdventureRuntime.onUi(duel::prepare);
        final float factor = AdventureRuntime.onUi(() -> AdventurePlayer.current().getDifficulty().enemyLifeFactor);
        final RegisteredPlayer ai = match.players().stream().filter(p -> p != match.human()).findFirst().orElseThrow();
        assertEquals(ai.getStartingLife(), Math.round(12 * factor));
        assertEquals((int) match.human().getStartingLife(), (int) AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife()));
        assertEquals(match.players().size(), 2);
        assertEquals(match.rules().getGamesPerMatch(), WorldData.getEnemy("Jodah").gamesPerMatch);
    }

    // Fails if a win does not pay the shard, take the enemy off the map and give its rewards, as WorldStage.setWinner does
    @Test
    public void winAppliesRewards() {
        final AdventureDuel duel = duel(jodah(12));
        final int shards = AdventureRuntime.onUi(() -> AdventurePlayer.current().getShards());
        final AdventureDuel.After after = AdventureRuntime.onUi(() -> duel.finish(true));
        assertTrue(AdventureRuntime.onUi(() -> AdventurePlayer.current().getShards()) >= shards + 1);
        assertEquals(removed.size(), 1);
        assertNotNull(after.reward());
        assertTrue(after.reward().steps().size() > 0);
        assertNull(after.defeat());
    }

    // Fails if a loss does not cost life and gold as AdventurePlayer.defeated does
    @Test
    public void lossAppliesPenalty() {
        final AdventureDuel duel = duel(jodah(12));
        final int life = AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife());
        final AdventureDuel.After after = AdventureRuntime.onUi(() -> duel.finish(false));
        final int expected = AdventureRuntime.onUi(() -> (int) (life - AdventurePlayer.current().getMaxLife() * AdventurePlayer.current().getDifficulty().lifeLoss));
        assertEquals((int) AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife()), expected);
        assertEquals(removed.size(), 1);
        assertNull(after.reward());
    }

    // Fails if leaving a duel early does not count as one loss, or counts it twice (Review Focus 3)
    @Test
    public void leavingEarlyIsLossOnce() {
        final AdventureDuel duel = duel(jodah(12));
        final int life = AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife());
        AdventureRuntime.onUi(() -> {
            duel.forfeit();
            duel.forfeit();
            return null;
        });
        final int expected = AdventureRuntime.onUi(() -> (int) (life - AdventurePlayer.current().getMaxLife() * AdventurePlayer.current().getDifficulty().lifeLoss));
        assertEquals((int) AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife()), expected);
        assertEquals(removed.size(), 1);
    }

    @Test
    public void finishedDuelIgnoresForfeit() {
        final AdventureDuel duel = duel(jodah(12));
        AdventureRuntime.onUi(() -> duel.finish(true));
        final int life = AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife());
        AdventureRuntime.onUi(() -> {
            duel.forfeit();
            return null;
        });
        assertEquals((int) AdventureRuntime.onUi(() -> AdventurePlayer.current().getLife()), life);
    }
}
