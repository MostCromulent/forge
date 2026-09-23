package forge.web;

import com.google.common.primitives.Ints;
import forge.game.GameLogVerbosity;
import forge.game.phase.PhaseType;
import forge.gamemodes.match.YieldController;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.ToBrowser.ServerSettings;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the options dialog reads and writes: Forge preferences the desktop client shares, and the phase stops. */
final class WebSettings {
    private WebSettings() {
    }

    private static final Map<String, FPref> BOOLEAN_PREFS = Map.of(
            "interruptAttackers", FPref.YIELD_INTERRUPT_ON_ATTACKERS,
            "interruptOpponentSpell", FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL,
            "interruptTargeting", FPref.YIELD_INTERRUPT_ON_TARGETING,
            "interruptTriggers", FPref.YIELD_INTERRUPT_ON_TRIGGERS,
            "interruptMassRemoval", FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL,
            "highlightPlayable", FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS,
            "autoTapPreview", FPref.UI_SHOW_AUTOTAP_PREVIEW);

    static ServerSettings values() {
        final ForgePreferences prefs = FModel.getPreferences();
        return new ServerSettings(
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("interruptAttackers")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("interruptOpponentSpell")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("interruptTargeting")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("interruptTriggers")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("interruptMassRemoval")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("highlightPlayable")),
                prefs.getPrefBoolean(BOOLEAN_PREFS.get("autoTapPreview")),
                prefs.getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS),
                ForgeConstants.AUTO_DECISION_PER_CARD.equals(prefs.getPref(FPref.UI_AUTO_DECISION_MODE)) ? "card" : "ability",
                GameLogVerbosity.fromString(prefs.getPref(FPref.DEV_LOG_ENTRY_TYPE)),
                prefs.getPref(FPref.UI_TARGETING_OVERLAY),
                "#" + prefs.getPref(FPref.UI_ACTIONABLE_HIGHLIGHT_COLOR),
                // A volume of zero is the off switch, so the two preferences are reported as one number
                prefs.getPrefBoolean(FPref.UI_ENABLE_SOUNDS) ? prefs.getPrefInt(FPref.UI_VOL_SOUNDS) : 0,
                prefs.getPrefBoolean(FPref.UI_ENABLE_MUSIC) ? prefs.getPrefInt(FPref.UI_VOL_MUSIC) : 0);
    }

    /** A value off the socket reaches the file the desktop client shares, so an illegal one is dropped. */
    static void set(final IGameController controller, final String key, final String value) {
        final ForgePreferences prefs = FModel.getPreferences();
        final FPref pref = BOOLEAN_PREFS.get(key);
        if (pref != null) {
            prefs.setPref(pref, Boolean.parseBoolean(value));
        } else if ("autoPassNoActions".equals(key)) {
            if (Boolean.parseBoolean(value) != prefs.getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS)) {
                YieldController.toggleAutoPassNoActions(controller);
            }
            return;
        } else if ("autoYieldMode".equals(key)) {
            prefs.setPref(FPref.UI_AUTO_DECISION_MODE,
                    "card".equals(value) ? ForgeConstants.AUTO_DECISION_PER_CARD : ForgeConstants.AUTO_DECISION_PER_ABILITY);
        } else if ("logDetail".equals(key)) {
            prefs.setPref(FPref.DEV_LOG_ENTRY_TYPE, GameLogVerbosity.fromString(value).toString());
        } else if ("arrows".equals(key)) {
            final Integer mode = Ints.tryParse(value);
            if (mode == null || mode < 0 || mode > 2) {
                Logger.warn("Web client: bad arrows setting {}", value);
                return;
            }
            prefs.setPref(FPref.UI_TARGETING_OVERLAY, value);
        } else if ("soundVolume".equals(key) || "musicVolume".equals(key)) {
            final Integer volume = Ints.tryParse(value);
            if (volume == null || volume < 0 || volume > 100) {
                Logger.warn("Web client: bad volume {}", value);
                return;
            }
            final boolean effects = "soundVolume".equals(key);
            prefs.setPref(effects ? FPref.UI_VOL_SOUNDS : FPref.UI_VOL_MUSIC, value);
            // Silence is off: the desktop client reads the switch, not the volume
            prefs.setPref(effects ? FPref.UI_ENABLE_SOUNDS : FPref.UI_ENABLE_MUSIC, volume > 0);
        } else {
            Logger.warn("Web client: unknown setting {}", key);
            return;
        }
        prefs.save();
    }

    /** The phases one row of the stop grid has a stop on; untap takes no stop, as on desktop. */
    static List<PhaseType> stops(final FPref[] keys) {
        final List<PhaseType> out = new ArrayList<>();
        final PhaseType[] phases = PhaseType.values();
        for (int i = 1; i < phases.length; i++) {
            if (FModel.getPreferences().getPrefBoolean(keys[i - 1])) {
                out.add(phases[i]);
            }
        }
        return out;
    }

    static FPref stopKey(final PhaseType phase, final boolean mine) {
        return (mine ? FPref.PHASES_HUMAN : FPref.PHASES_AI)[phase.ordinal() - 1];
    }
}
