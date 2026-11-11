package forge.web.adventure;

import forge.adventure.data.DifficultyData;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.card.ColorSet;
import forge.localinstance.properties.ForgeConstants;
import forge.web.WebTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Boots the adventure model on Realm of Legends and makes worlds for the tests. */
public final class AdventureFixture {
    public static final String PLANE = "Realm of Legends";
    private static boolean booted;

    private AdventureFixture() {}

    /** Config reads the plane from the user's settings once, when it is first used, so the settings are written before that. */
    public static synchronized void boot() {
        WebTestSupport.initModel();
        try {
            final Path settings = Path.of(ForgeConstants.USER_ADVENTURE_DIR, "settings.json");
            Files.createDirectories(settings.getParent());
            if (!Files.exists(settings)) {
                Files.writeString(settings, "{\"plane\":\"" + PLANE + "\"}", StandardCharsets.UTF_8);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        if (!booted) {
            AdventureRuntime.start();
            // Without a host the model would build the mobile stages, which need a GL context
            AdventureHost.set(new WebAdventureHost());
            booted = true;
        }
    }

    public static WorldSave newWorld(final long seed) {
        boot();
        // Realm of Legends offers only commander starter decks
        return AdventureRuntime.onUi(() -> {
            final DifficultyData normal = Config.instance().getConfigData().difficulties[1];
            return WorldSave.generateNewWorld("Tester", false, 0, 0, ColorSet.fromNames("W"), normal, AdventureModes.Commander, 0, null, seed);
        });
    }
}
