package chronos.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical encoding (D-09). Jackson writes the bytes, but CHRONOS owns the rules:
 * only long, string, boolean, null, list and string-keyed map values are encodable; map keys are sorted;
 * floating point values and unordered sets are refused because they would make equal states encode differently.
 * Equal values therefore always produce equal bytes, which is what makes digests comparable (Section 14).
 */
public final class Canon {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Canon() {}

    /** Deep-copies a value into canonical form: Long numbers, sorted maps, plain lists. */
    public static Object normalize(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long) return v;
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof BigInteger bi) return bi.longValueExact();
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> t = new TreeMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) {
                if (!(en.getKey() instanceof String k))
                    throw new IllegalArgumentException("canonical map keys must be strings, got: " + en.getKey());
                t.put(k, normalize(en.getValue()));
            }
            return t;
        }
        if (v instanceof Set<?> s && !(v instanceof SortedSet<?>))
            throw new IllegalArgumentException("unordered Set is not canonically encodable (use a List or SortedSet)");
        if (v instanceof Collection<?> c) {
            ArrayList<Object> l = new ArrayList<>(c.size());
            for (Object o : c) l.add(normalize(o));
            return l;
        }
        throw new IllegalArgumentException("value is not canonically encodable (use long, string, boolean, list, map): "
                + v.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeMap(Map<String, ?> m) {
        return (Map<String, Object>) normalize(m);
    }

    /** Canonical JSON text of a value. */
    public static String json(Object v) {
        try {
            return MAPPER.writeValueAsString(normalize(v));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode value", e);
        }
    }

    /** Parses JSON text into canonical form (sorted maps, Long numbers). */
    public static Object parse(String text) {
        try {
            return normalize(MAPPER.readValue(text, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed JSON: " + e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) o;
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Digest of the canonical encoding of a value. */
    public static String digest(Object v) {
        return sha256(json(v));
    }

    // ----- small typed accessors used all over the code base -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    public static long num(Object o) {
        return ((Number) o).longValue();
    }

    public static String str(Object o) {
        return (String) o;
    }
}
