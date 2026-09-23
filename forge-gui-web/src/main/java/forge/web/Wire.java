package forge.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the protocol's records become JSON and back. Every message the browser receives or sends is a record in
 * {@link ToBrowser} or {@link FromBrowser}; the browser's TypeScript types are generated from those records, so
 * a field is named in one place only.
 *
 * <p>A null component is left out of the JSON, which the TypeScript sees as an optional field. Only components
 * marked {@link Nullable} may be null; the tests run with assertions on and fail on any other.
 */
final class Wire {
    private Wire() {
    }

    /** A message to the browser, sent as {@code {"t": value, ...}}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Message {
        String value();
    }

    /** A message from the browser. Empty when the record has a {@code t} component naming several at once. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Command {
        String value() default "";
    }

    /** A question the game waits on, sent as {@code {"t": "request", "id", "kind": value, ...}}. Empty when a
     *  {@code kind} component names several kinds that share one shape. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Request {
        String value() default "";
    }

    /** Something that happened in the game, sent as {@code {"kind": value, ...}} inside a state message. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Event {
        String value();
    }

    /** A component that may be null, and is then left out. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Nullable {
    }

    /** The field's name on the wire, for a name Java cannot use. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Name {
        String value();
    }

    /** The field's TypeScript type, for a component whose Java type says too little (a raw JSON tree). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Ts {
        String value();
    }

    private static final Gson GSON = new Gson();
    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();

    /** The JSON for a message to the browser, with its {@code t}. */
    static JsonObject encode(final Record message) {
        final JsonObject out = new JsonObject();
        final Message m = message.getClass().getAnnotation(Message.class);
        if (m != null) {
            out.addProperty("t", m.value());
        }
        fill(out, message);
        return out;
    }

    /** The JSON for a request's own fields, without the id the waiting queue gives it. */
    static JsonObject encodeRequest(final Record request) {
        final JsonObject out = new JsonObject();
        out.addProperty("t", "request");
        final Request r = request.getClass().getAnnotation(Request.class);
        if (r != null && !r.value().isEmpty()) {
            out.addProperty("kind", r.value());
        }
        fill(out, request);
        return out;
    }

    private static void fill(final JsonObject out, final Record record) {
        for (final RecordComponent c : components(record.getClass())) {
            final Object value = read(c, record);
            if (value == null) {
                assert c.isAnnotationPresent(Nullable.class)
                        : record.getClass().getSimpleName() + "." + c.getName() + " is null but not @Nullable";
                continue;
            }
            out.add(nameOf(c), toJson(value));
        }
    }

    static JsonElement toJson(final Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement e) {
            return e;
        }
        if (value instanceof Record r) {
            final JsonObject o = new JsonObject();
            final Event event = r.getClass().getAnnotation(Event.class);
            if (event != null) {
                o.addProperty("kind", event.value());
            }
            fill(o, r);
            return o;
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (value instanceof Collection<?> list) {
            final JsonArray a = new JsonArray();
            list.forEach(item -> a.add(toJson(item)));
            return a;
        }
        if (value instanceof Map<?, ?> map) {
            final JsonObject o = new JsonObject();
            map.forEach((k, v) -> o.add(String.valueOf(k), toJson(v)));
            return o;
        }
        throw new IllegalArgumentException("No JSON form for " + value.getClass().getName());
    }

    /** A message from the browser as the record its {@code t} names. */
    static <T extends Record> T decode(final JsonObject message, final Class<T> type) {
        assert commandNames(type).contains(message.get("t").getAsString())
                : type.getSimpleName() + " does not read " + message.get("t");
        return GSON.fromJson(message, type);
    }

    /** Every {@code t} a command record reads: its own, or each constant of its {@code t} component. */
    static List<String> commandNames(final Class<?> type) {
        final Command c = type.getAnnotation(Command.class);
        if (c == null) {
            return List.of();
        }
        if (!c.value().isEmpty()) {
            return List.of(c.value());
        }
        return constantsOf(type, "t");
    }

    /** Every {@code kind} a request record is sent as. */
    static List<String> requestKinds(final Class<?> type) {
        final Request r = type.getAnnotation(Request.class);
        if (r == null) {
            return List.of();
        }
        return r.value().isEmpty() ? constantsOf(type, "kind") : List.of(r.value());
    }

    private static List<String> constantsOf(final Class<?> type, final String component) {
        for (final RecordComponent rc : components(type)) {
            if (rc.getName().equals(component) && rc.getType().isEnum()) {
                final List<String> names = new ArrayList<>();
                for (final Object constant : rc.getType().getEnumConstants()) {
                    names.add(((Enum<?>) constant).name());
                }
                return names;
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " names no " + component + " and has no enum " + component + " component");
    }

    static RecordComponent[] components(final Class<?> type) {
        return COMPONENTS.computeIfAbsent(type, Class::getRecordComponents);
    }

    static String nameOf(final RecordComponent c) {
        final Name n = c.getAnnotation(Name.class);
        return n == null ? c.getName() : n.value();
    }

    private static Object read(final RecordComponent c, final Record record) {
        try {
            return c.getAccessor().invoke(record);
        } catch (final IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + c.getName(), e);
        }
    }
}
