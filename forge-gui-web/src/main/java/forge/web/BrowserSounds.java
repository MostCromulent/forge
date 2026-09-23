package forge.web;

import forge.game.event.GameEvent;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.PlayerView;
import forge.sound.EventVisualizer;
import forge.sound.SoundEffectType;
import forge.web.ToBrowser.Sound;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The sound a game event makes. Desktop plays the same sounds from the same events; here the browser plays them, so
 * only the name travels. Two of them depend on whose view this is, which the GUI answers.
 */
final class BrowserSounds {
    private final EventVisualizer sounds;

    BrowserSounds(final Supplier<PlayerView> currentPlayer, final Predicate<PlayerView> isLocal) {
        sounds = new EventVisualizer(null) {
            @Override
            public SoundEffectType visit(final GameEventGameOutcome event) {
                final PlayerView local = currentPlayer.get();
                return local != null && local.getLobbyPlayerName().equals(event.winningPlayerName())
                        ? SoundEffectType.WinDuel : SoundEffectType.LoseDuel;
            }

            @Override
            public SoundEffectType visit(final GameEventBlockersDeclared event) {
                // Your own blocks already made their sound as you declared them
                return isLocal.test(event.defendingPlayer()) ? null : SoundEffectType.Block;
            }
        };
    }

    /** The sound this event makes, or null if it makes none. */
    Sound soundFor(final GameEvent event) {
        final SoundEffectType effect = event.visit(sounds);
        if (effect == null) {
            return null;
        }
        final String name = effect == SoundEffectType.ScriptedEffect
                ? sounds.getScriptedSoundEffectName(event) : effect.getResourceFileName();
        return name == null || name.isEmpty() ? null : new Sound(name, effect.isSynced());
    }
}
