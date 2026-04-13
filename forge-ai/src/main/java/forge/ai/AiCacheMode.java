package forge.ai;

/**
 * Controls AI combat cache behavior via {@code -Dai.cache=disabled|verify|enabled}.
 *
 * <ul>
 *   <li>{@code disabled} — no caching, original code paths (default)</li>
 *   <li>{@code verify} — caching active, every cache hit is double-checked against
 *       the real computation and assertion-checked for equality</li>
 *   <li>{@code enabled} — caching active, no verification overhead</li>
 * </ul>
 */
public enum AiCacheMode {
    DISABLED,
    VERIFY,
    ENABLED;

    private static final AiCacheMode INSTANCE = fromSystemProperty();

    public static AiCacheMode get() {
        return INSTANCE;
    }

    public boolean isCacheActive() {
        return this != DISABLED;
    }

    public boolean isVerify() {
        return this == VERIFY;
    }

    private static AiCacheMode fromSystemProperty() {
        String value = System.getProperty("ai.cache", "disabled");
        switch (value.toLowerCase()) {
            case "enabled": return ENABLED;
            case "verify": return VERIFY;
            default: return DISABLED;
        }
    }
}
