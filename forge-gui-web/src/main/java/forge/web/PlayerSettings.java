package forge.web;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.EnumMap;
import java.util.Map;

/**
 * One player's settings. Every browser reaches Forge through the process's one set of preferences, which is the
 * host's: the host's settings are those preferences, saved and shared with the desktop client, while a guest's
 * start from Forge's defaults and live in its session. Anything a player can set is read through here, never
 * straight from the preferences, so one player's choice cannot become another's.
 */
final class PlayerSettings {
    /** Null for the host, whose settings are the preferences themselves. */
    private final Map<FPref, String> own;

    private PlayerSettings(final Map<FPref, String> own) {
        this.own = own;
    }

    /** The host's: Forge's preferences, which the desktop client shares. */
    static PlayerSettings saved() {
        return new PlayerSettings(null);
    }

    /** A guest's, starting from Forge's defaults. */
    static PlayerSettings fresh() {
        return new PlayerSettings(new EnumMap<>(FPref.class));
    }

    synchronized String get(final FPref pref) {
        if (own == null) {
            return FModel.getPreferences().getPref(pref);
        }
        final String value = own.get(pref);
        return value != null ? value : pref.getDefault();
    }

    /** Whether these are the host's, which are Forge's own preferences. */
    boolean shared() {
        return own == null;
    }

    boolean getBoolean(final FPref pref) {
        return Boolean.parseBoolean(get(pref));
    }

    int getInt(final FPref pref) {
        try {
            return Integer.parseInt(get(pref));
        } catch (final NumberFormatException e) {
            return Integer.parseInt(pref.getDefault());
        }
    }

    synchronized void set(final FPref pref, final String value) {
        if (own == null) {
            FModel.getPreferences().setPref(pref, value);
        } else {
            own.put(pref, value);
        }
    }

    void set(final FPref pref, final boolean value) {
        set(pref, String.valueOf(value));
    }

    /** Keeps what was set. Only the host's settings outlive the process; a guest's browser remembers its own. */
    void save() {
        if (own == null) {
            FModel.getPreferences().save();
        }
    }
}
