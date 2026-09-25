package forge.web;

import com.google.common.primitives.Ints;
import forge.game.GameLogVerbosity;
import forge.game.phase.PhaseType;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.web.ToBrowser.ServerSettings;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the options dialog reads and writes: a player's own settings (see {@link PlayerSettings}), and the phase
 * stops. The host's are the Forge preferences the desktop client shares.
 */
final class WebSettings {
    private WebSettings() {
    }

    private static final Map<String, FPref> BOOLEAN_PREFS = Map.of(
            "interruptAttackers", FPref.YIELD_INTERRUPT_ON_ATTACKERS,
            "interruptOpponentSpell", FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL,
            "interruptTargeting", FPref.YIELD_INTERRUPT_ON_TARGETING,
            "interruptTriggers", FPref.YIELD_INTERRUPT_ON_TRIGGERS,
            "interruptMassRemoval", FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL,
            "autoTapPreview", FPref.UI_SHOW_AUTOTAP_PREVIEW,
            "autoPassNoActions", FPref.YIELD_AUTO_PASS_NO_ACTIONS);

    static ServerSettings values(final PlayerSettings player) {
        return new ServerSettings(
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_TARGETING),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_TRIGGERS),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL),
                player.getBoolean(FPref.UI_SHOW_AUTOTAP_PREVIEW),
                ForgeConstants.AUTO_DECISION_PER_CARD.equals(player.get(FPref.UI_AUTO_DECISION_MODE)) ? "card" : "ability",
                GameLogVerbosity.fromString(player.get(FPref.DEV_LOG_ENTRY_TYPE)),
                player.get(FPref.UI_TARGETING_OVERLAY),
                player.getBoolean(FPref.DEV_MODE_ENABLED),
                "#" + player.get(FPref.UI_ACTIONABLE_HIGHLIGHT_COLOR),
                // A volume of zero is the off switch, so the two preferences are reported as one number
                player.getBoolean(FPref.UI_ENABLE_SOUNDS) ? player.getInt(FPref.UI_VOL_SOUNDS) : 0,
                player.getBoolean(FPref.UI_ENABLE_MUSIC) ? player.getInt(FPref.UI_VOL_MUSIC) : 0);
    }

    /**
     * Saves a setting. A value off the socket can reach the file the desktop client shares, so an illegal one is
     * dropped. The controller is the game's, if one has started: a browser can change a setting (its first-run
     * defaults do) before a match begins, and that must not be lost.
     */
    static void set(final PlayerSettings player, final IGameController controller, final String key, final String value) {
        final FPref pref = BOOLEAN_PREFS.get(key);
        if (pref != null) {
            // The host decides what to interrupt and what to highlight from its own copy of these, seeded when the
            // game opened, so a change mid-game has to reach it as well. Before then the seed carries it.
            setEverywhere(player, controller, pref, String.valueOf(Boolean.parseBoolean(value)));
        } else if ("devMode".equals(key)) {
            final boolean on = Boolean.parseBoolean(value);
            player.set(FPref.DEV_MODE_ENABLED, on);
            // Desktop keeps the switch in a static as well, which the draft code and debug output read
            if (player.shared()) {
                ForgePreferences.DEV_MODE = on;
            }
        } else if ("autoYieldMode".equals(key)) {
            setEverywhere(player, controller, FPref.UI_AUTO_DECISION_MODE,
                    "card".equals(value) ? ForgeConstants.AUTO_DECISION_PER_CARD : ForgeConstants.AUTO_DECISION_PER_ABILITY);
        } else if ("logDetail".equals(key)) {
            player.set(FPref.DEV_LOG_ENTRY_TYPE, GameLogVerbosity.fromString(value).toString());
        } else if ("arrows".equals(key)) {
            final Integer mode = Ints.tryParse(value);
            if (mode == null || mode < 0 || mode > 2) {
                Logger.warn("Web client: bad arrows setting {}", value);
                return;
            }
            player.set(FPref.UI_TARGETING_OVERLAY, value);
        } else if ("soundVolume".equals(key) || "musicVolume".equals(key)) {
            final Integer volume = Ints.tryParse(value);
            if (volume == null || volume < 0 || volume > 100) {
                Logger.warn("Web client: bad volume {}", value);
                return;
            }
            final boolean effects = "soundVolume".equals(key);
            player.set(effects ? FPref.UI_VOL_SOUNDS : FPref.UI_VOL_MUSIC, value);
            // Silence is off: the desktop client reads the switch, not the volume
            player.set(effects ? FPref.UI_ENABLE_SOUNDS : FPref.UI_ENABLE_MUSIC, volume > 0);
        } else {
            Logger.warn("Web client: unknown setting {}", key);
            return;
        }
        player.save();
    }

    /** A setting the game reads as well as the player: the player's own copy, and the game's copy of it, if any. */
    private static void setEverywhere(final PlayerSettings player, final IGameController controller, final FPref pref,
            final String value) {
        player.set(pref, value);
        if (controller != null) {
            applyTo(controller, pref, value);
        }
    }

    /**
     * Gives a game this player's settings in place of the shared preferences it would otherwise read: the host's
     * engine keeps a copy per player, and the client's own controller reads the auto-yield mode.
     *
     * <p>Auto-pass always stops where the interrupts say. Forge leaves that off unless asked, which makes the
     * interrupts stop only a yield such as End Turn; here they are offered as what stops auto-passing, so they do.
     * It is given to the game only, never saved, so the desktop client keeps its own choice. Playable cards are
     * always highlighted the same way, because this client has no other sign of what can be played.</p>
     */
    static void applyAll(final PlayerSettings player, final IGameController controller) {
        for (final FPref pref : PlayerSettings.PER_PLAYER_ON_HOST) {
            final boolean always = pref == FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS || pref == FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS;
            applyTo(controller, pref, always ? "true" : player.get(pref));
        }
        applyTo(controller, FPref.UI_AUTO_DECISION_MODE, player.get(FPref.UI_AUTO_DECISION_MODE));
    }

    private static void applyTo(final IGameController controller, final FPref pref, final String value) {
        if (controller.getYieldController() != null) {
            controller.getYieldController().setPref(pref, value);
        }
        // The host's engine keeps no copy of the auto-yield mode: the client decides how a choice is stored
        if (pref != FPref.UI_AUTO_DECISION_MODE) {
            controller.setYieldPref(pref, value);
        }
    }

    /** The phases one row of the stop grid has a stop on; untap takes no stop, as on desktop. */
    static List<PhaseType> stops(final PlayerSettings player, final FPref[] keys) {
        final List<PhaseType> out = new ArrayList<>();
        final PhaseType[] phases = PhaseType.values();
        for (int i = 1; i < phases.length; i++) {
            if (player.getBoolean(keys[i - 1])) {
                out.add(phases[i]);
            }
        }
        return out;
    }

    /**
     * Sets every stop of one row: those listed on, the rest off. Untap takes no stop, as on desktop. Answers the
     * phases that changed, which a game already under way has to be told of.
     */
    static List<PhaseType> setStops(final PlayerSettings player, final boolean mine, final List<PhaseType> phases) {
        final List<PhaseType> changed = new ArrayList<>();
        for (final PhaseType phase : PhaseType.values()) {
            final boolean stop = phases.contains(phase);
            if (phase.ordinal() > 0 && player.getBoolean(stopKey(phase, mine)) != stop) {
                player.set(stopKey(phase, mine), stop);
                changed.add(phase);
            }
        }
        if (!changed.isEmpty()) {
            player.save();
        }
        return changed;
    }

    static FPref stopKey(final PhaseType phase, final boolean mine) {
        return (mine ? FPref.PHASES_HUMAN : FPref.PHASES_AI)[phase.ordinal() - 1];
    }
}
