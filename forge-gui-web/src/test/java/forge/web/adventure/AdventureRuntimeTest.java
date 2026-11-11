package forge.web.adventure;

import com.badlogic.gdx.graphics.Pixmap;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.WorldData;
import forge.adventure.util.Config;
import forge.adventure.world.World;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class AdventureRuntimeTest {
    @Test
    public void generatesRealmOfLegendsWorld() {
        final World world = AdventureFixture.newWorld(42).getWorld();
        assertTrue(Config.instance().getPlanePath(Config.instance().getSettingData().plane).contains(AdventureFixture.PLANE));
        assertEquals(world.getWidthInTiles(), 700);
        assertEquals(world.getChunkSize(), 30);
    }

    @Test
    public void enemySpriteWithoutGl() {
        AdventureFixture.boot();
        final EnemySprite sprite = AdventureRuntime.onUi(() -> new EnemySprite(WorldData.getEnemy("Jodah")));
        assertTrue(sprite.getWidth() > 0);
    }

    @Test
    public void biomeSpriteDraws() {
        final Pixmap tile = AdventureFixture.newWorld(42).getWorld().getBiomeSprite(10, 10);
        assertEquals(tile.getWidth(), 16);
        boolean opaque = false;
        for (int x = 0; x < 16 && !opaque; x++) {
            for (int y = 0; y < 16 && !opaque; y++) {
                opaque = (tile.getPixel(x, y) & 0xff) != 0;
            }
        }
        assertTrue(opaque);
    }
}
