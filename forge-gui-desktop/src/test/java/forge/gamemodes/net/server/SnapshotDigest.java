package forge.gamemodes.net.server;

import forge.game.GameView;
import forge.trackable.TrackableProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A comparable summary of everything a view graph holds.
 *
 * <p>Exists so a test can ask the one question the checksum answers by sampling — does the
 * client hold what the server holds — and get an exact answer instead of a probabilistic one.
 * Both sides reduce their own graph the same way and the strings are compared, so nothing
 * here re-implements how a client applies a delta: the client's own graph, built by its own
 * apply, is the input.
 *
 * <p>Lives in the test tree, in the snapshot's package, so it can reach a package-private
 * builder without widening anything in production.
 */
public final class SnapshotDigest {

    private SnapshotDigest() {}

    /** Every object and property the graph holds, in a fixed order. Null for no graph. */
    public static String of(final GameView gameView) {
        if (gameView == null) {
            return null;
        }
        final Map<Integer, Map<TrackableProperty, Object>> objects = ViewSnapshot.build(gameView).objects();
        final List<Integer> keys = new ArrayList<>(objects.keySet());
        Collections.sort(keys);

        final StringBuilder sb = new StringBuilder();
        for (final Integer key : keys) {
            final Map<TrackableProperty, Object> props = objects.get(key);
            final List<TrackableProperty> names = new ArrayList<>(props.keySet());
            names.sort(java.util.Comparator.comparing(Enum::name));
            for (final TrackableProperty prop : names) {
                sb.append(key).append('.').append(prop.name()).append('=')
                        .append(ViewSnapshot.canonical(props.get(prop))).append('\n');
            }
        }
        return sb.toString();
    }

    /** A short stable form of {@link #of}, for carrying between processes. */
    public static String shortDigest(final GameView gameView) {
        final String full = of(gameView);
        return full == null ? "none" : Integer.toHexString(full.hashCode()) + ":" + full.length();
    }
}
