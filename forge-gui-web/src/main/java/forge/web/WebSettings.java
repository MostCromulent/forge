package forge.web;

import com.google.common.primitives.Ints;
import forge.MulliganDefs;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.game.GameLogVerbosity;
import forge.game.phase.PhaseType;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.gamemodes.match.DeclineScope;
import forge.gamemodes.match.YieldController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.web.protocol.ToBrowser.ServerSettings;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** What the options dialog reads and writes: a player's own settings (see {@link PlayerSettings}) and the phase stops. */
public final class WebSettings {
    private WebSettings() {
    }

    private static final Map<String, FPref> BOOLEAN_PREFS = Map.ofEntries(
            Map.entry("interruptAttackers", FPref.YIELD_INTERRUPT_ON_ATTACKERS),
            Map.entry("interruptOpponentSpell", FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL),
            Map.entry("interruptTargeting", FPref.YIELD_INTERRUPT_ON_TARGETING),
            Map.entry("interruptTriggers", FPref.YIELD_INTERRUPT_ON_TRIGGERS),
            Map.entry("interruptMassRemoval", FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL),
            Map.entry("interruptReveal", FPref.YIELD_INTERRUPT_ON_REVEAL),
            Map.entry("suppressAfterYield", FPref.YIELD_SUPPRESS_AFTER_END),
            Map.entry("suppressOwnTurn", FPref.YIELD_SUPPRESS_ON_OWN_TURN),
            Map.entry("skipPhaseDelay", FPref.YIELD_SKIP_PHASE_DELAY),
            Map.entry("skipResolveDelay", FPref.YIELD_SKIP_RESOLVE_DELAY),
            Map.entry("autoTapPreview", FPref.UI_SHOW_AUTOTAP_PREVIEW),
            Map.entry("autoPassNoActions", FPref.YIELD_AUTO_PASS_NO_ACTIONS));

    /** When each yield suggestion is offered again after it is declined, by its DeclineScope name. */
    private static final Map<String, FPref> SCOPE_PREFS = Map.of(
            "suggestStackYield", FPref.YIELD_DECLINE_SCOPE_STACK_YIELD,
            "suggestNoActions", FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS);

    public static ServerSettings values(final PlayerSettings player) {
        return new ServerSettings(
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_TARGETING),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_TRIGGERS),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL),
                player.getBoolean(FPref.YIELD_INTERRUPT_ON_REVEAL),
                DeclineScope.fromPref(player.get(FPref.YIELD_DECLINE_SCOPE_STACK_YIELD)).name(),
                DeclineScope.fromPref(player.get(FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS)).name(),
                player.getBoolean(FPref.YIELD_SUPPRESS_AFTER_END),
                player.getBoolean(FPref.YIELD_SUPPRESS_ON_OWN_TURN),
                player.getBoolean(FPref.YIELD_SKIP_PHASE_DELAY),
                player.getBoolean(FPref.YIELD_SKIP_RESOLVE_DELAY),
                player.getBoolean(FPref.UI_SHOW_AUTOTAP_PREVIEW),
                ForgeConstants.AUTO_DECISION_PER_CARD.equals(player.get(FPref.UI_AUTO_DECISION_MODE)) ? "card" : "ability",
                GameLogVerbosity.fromString(player.get(FPref.DEV_LOG_ENTRY_TYPE)),
                player.get(FPref.UI_TARGETING_OVERLAY),
                player.getBoolean(FPref.DEV_MODE_ENABLED),
                "#" + player.get(FPref.UI_ACTIONABLE_HIGHLIGHT_COLOR),
                // A volume of zero is the off switch, so the two preferences are reported as one number
                player.getBoolean(FPref.UI_ENABLE_SOUNDS) ? player.getInt(FPref.UI_VOL_SOUNDS) : 0,
                player.getBoolean(FPref.UI_ENABLE_MUSIC) ? player.getInt(FPref.UI_VOL_MUSIC) : 0,
                player.getBoolean(FPref.FILTERED_HANDS),
                MulliganDefs.GetRuleByName(player.get(FPref.MULLIGAN_RULE)).name(),
                player.get(FPref.UI_CURRENT_AI_PROFILE),
                player.getBoolean(FPref.UI_ANTE));
    }

    /** Saves a setting, dropping an illegal value because it could reach the file the desktop client shares. */
    public static void set(final PlayerSettings player, final IGameController controller, final String key, final String value) {
        final FPref pref = BOOLEAN_PREFS.get(key);
        if (pref != null) {
            // The host decides from its own copy of these, seeded when the game opened, so a change mid-game has to reach it as well
            setEverywhere(player, controller, pref, String.valueOf(Boolean.parseBoolean(value)));
        } else if (SCOPE_PREFS.containsKey(key)) {
            setEverywhere(player, controller, SCOPE_PREFS.get(key), DeclineScope.fromPref(value).name());
        } else if ("devMode".equals(key)) {
            final boolean on = Boolean.parseBoolean(value);
            player.set(FPref.DEV_MODE_ENABLED, on);
            // Desktop keeps the switch in a static as well, which the draft code and debug output read
            if (player.shared()) {
                ForgePreferences.DEV_MODE = on;
            }
        } else if (TABLE_SETTINGS.contains(key)) {
            // These are the rules of the host's table, so a guest's browser has no say in them
            if (!player.shared() || !setTableRule(player, key, value)) {
                return;
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

    private static final List<String> TABLE_SETTINGS = List.of("filteredHands", "mulliganRule", "aiProfile", "ante");

    /** Sets one of the host's table rules, and answers false for a value Forge does not know. */
    private static boolean setTableRule(final PlayerSettings player, final String key, final String value) {
        switch (key) {
            case "filteredHands" -> {
                final boolean on = Boolean.parseBoolean(value);
                player.set(FPref.FILTERED_HANDS, on);
                // Forge copies this into StaticData only when it starts, which is why desktop asks for a restart
                StaticData.instance().setFilteredHandsEnabled(on);
            }
            case "mulliganRule" -> {
                final MulliganDefs.MulliganRule rule = Arrays.stream(MulliganDefs.MulliganRule.values())
                        .filter(r -> r.name().equals(value)).findFirst().orElse(null);
                if (rule == null) {
                    Logger.warn("Web client: bad mulligan rule {}", value);
                    return false;
                }
                player.set(FPref.MULLIGAN_RULE, rule.name());
                StaticData.instance().setMulliganRule(rule);
            }
            case "aiProfile" -> {
                if (!AiProfileUtil.getProfilesDisplayList().contains(value)) {
                    Logger.warn("Web client: bad AI profile {}", value);
                    return false;
                }
                player.set(FPref.UI_CURRENT_AI_PROFILE, value);
            }
            default -> player.set(FPref.UI_ANTE, Boolean.parseBoolean(value));
        }
        return true;
    }

    /** A setting the game reads as well as the player: the player's own copy, and the game's copy of it, if any. */
    private static void setEverywhere(final PlayerSettings player, final IGameController controller, final FPref pref,
            final String value) {
        player.set(pref, value);
        if (controller != null) {
            applyTo(controller, pref, value);
        }
    }

    /** Gives a game this player's settings, except auto-pass always respects the interrupts and playable cards are always highlighted. */
    public static void applyAll(final PlayerSettings player, final IGameController controller) {
        // Every one is pushed after the engine's own seed, which read the shared preferences, so a guest plays by its own
        for (final FPref pref : YieldController.SYNCED_PREFS) {
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
    public static List<PhaseType> stops(final PlayerSettings player, final FPref[] keys) {
        final List<PhaseType> out = new ArrayList<>();
        final PhaseType[] phases = PhaseType.values();
        for (int i = 1; i < phases.length; i++) {
            if (player.getBoolean(keys[i - 1])) {
                out.add(phases[i]);
            }
        }
        return out;
    }

    /** Sets every stop of one row, those listed on and the rest off, and answers the phases that changed. */
    public static List<PhaseType> setStops(final PlayerSettings player, final boolean mine, final List<PhaseType> phases) {
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

    public static FPref stopKey(final PhaseType phase, final boolean mine) {
        return (mine ? FPref.PHASES_HUMAN : FPref.PHASES_AI)[phase.ordinal() - 1];
    }
}
