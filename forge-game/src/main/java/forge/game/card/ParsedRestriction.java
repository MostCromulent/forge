package forge.game.card;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pre-parsed form of an isValid restriction string. Restriction strings
 * like "Creature.Goblin+attacking+!tapped" are immutable — they originate
 * from card scripts and are reused across the whole game. Parsing them
 * once and caching is a pure-function memoization (H010).
 */
public final class ParsedRestriction {
    private static final String[] NO_EXCLUSIVES = new String[0];
    private static final String[] NO_COMMA = new String[0];
    private static final ConcurrentMap<String, ParsedRestriction> SINGLE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String[]> COMMA_CACHE = new ConcurrentHashMap<>();

    public final boolean typeNegated;
    public final String typeStr;
    public final String[] exclusives;

    private ParsedRestriction(boolean negated, String type, String[] exclusives) {
        this.typeNegated = negated;
        this.typeStr = type;
        this.exclusives = exclusives;
    }

    public static ParsedRestriction of(String restriction) {
        ParsedRestriction p = SINGLE_CACHE.get(restriction);
        if (p != null) return p;
        return SINGLE_CACHE.computeIfAbsent(restriction, ParsedRestriction::parse);
    }

    public static ParsedRestriction parse(String restriction) {
        String[] incR = restriction.split("\\.", 2);
        boolean negated = false;
        String type = incR[0];
        if (!type.isEmpty() && type.charAt(0) == '!') {
            negated = true;
            type = type.substring(1);
        }
        String[] exclusives = incR.length > 1 ? incR[1].split("\\+") : NO_EXCLUSIVES;
        return new ParsedRestriction(negated, type, exclusives);
    }

    public static String[] commaSplit(String restriction) {
        String[] s = COMMA_CACHE.get(restriction);
        if (s != null) return s;
        return COMMA_CACHE.computeIfAbsent(restriction, r -> r.isEmpty() ? NO_COMMA : r.split(","));
    }
}
