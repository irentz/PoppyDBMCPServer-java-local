package de.genios.mcp.poppydb;

import de.caluga.morphium.driver.MorphiumId;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free JSON parser/serializer with MongoDB Extended JSON (relaxed) support.
 *
 * <p>Parsing turns Extended JSON wrappers into the Java types Morphium's BSON encoder understands
 * ({"$oid"} → MorphiumId, {"$date"} → Date, {"$numberLong"} → Long, {"$numberDecimal"} → BigDecimal,
 * {"$regularExpression"}/{"$regex"} → Pattern, {"$binary"}/{"$uuid"} → byte[]/UUID).
 * Serializing does the reverse, so query results can be shown to the model losslessly.</p>
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < s.length()) {
            throw p.err("unexpected trailing content");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("JSON object expected");
        }
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("Invalid JSON at position " + pos + ": " + msg);
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw err("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw err("unexpected character '" + c + "'");
            }
        }

        void literal(String l) {
            if (!s.startsWith(l, pos)) {
                throw err("expected " + l);
            }
            pos += l.length();
        }

        Object object() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw err("object key expected");
                }
                String k = string();
                ws();
                if (peek() != ':') {
                    throw err("':' expected");
                }
                pos++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                pos++;
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw err("',' or '}' expected");
                }
            }
            return fromExtended(m);
        }

        List<Object> array() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                pos++;
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw err("',' or ']' expected");
                }
            }
            return l;
        }

        char peek() {
            if (pos >= s.length()) {
                throw err("unexpected end");
            }
            return s.charAt(pos);
        }

        String string() {
            pos++; // "
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw err("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            boolean fp = false;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fp = true;
                    pos++;
                } else {
                    break;
                }
            }
            String n = s.substring(start, pos);
            try {
                if (fp) {
                    return Double.parseDouble(n);
                }
                long l = Long.parseLong(n);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException e) {
                return new BigDecimal(n).doubleValue();
            }
        }
    }

    /** Converts Extended JSON wrapper objects into Java values (returns the map unchanged otherwise). */
    @SuppressWarnings("unchecked")
    private static Object fromExtended(Map<String, Object> m) {
        if (m.isEmpty() || !m.keySet().iterator().next().startsWith("$")) {
            return m;
        }
        try {
            if (m.size() == 1) {
                Map.Entry<String, Object> e = m.entrySet().iterator().next();
                Object v = e.getValue();
                switch (e.getKey()) {
                    case "$oid":
                        return new MorphiumId((String) v);
                    case "$date":
                        // nested {"$numberLong": ".."} has already been converted to Long here
                        if (v instanceof Number) {
                            return new Date(((Number) v).longValue());
                        }
                        if (v instanceof String) {
                            return Date.from(OffsetDateTime.parse((String) v).toInstant());
                        }
                        break;
                    case "$numberLong":
                        return Long.parseLong(v.toString());
                    case "$numberInt":
                        return Integer.parseInt(v.toString());
                    case "$numberDouble":
                        return Double.parseDouble(v.toString());
                    case "$numberDecimal":
                        return new BigDecimal(v.toString());
                    case "$uuid":
                        return UUID.fromString(v.toString());
                    case "$regularExpression": {
                        Map<String, Object> r = (Map<String, Object>) v;
                        return Pattern.compile((String) r.get("pattern"), flags((String) r.get("options")));
                    }
                    case "$binary": {
                        if (v instanceof Map) {
                            Map<String, Object> b = (Map<String, Object>) v;
                            return binary((String) b.get("base64"), (String) b.get("subType"));
                        }
                        break;
                    }
                    default:
                        break;
                }
            } else if (m.size() == 2) {
                // {$regex: "..", $options: ".."} is also a valid *query operator* - it is kept as-is,
                // the server understands it natively.
                if (m.containsKey("$binary") && m.containsKey("$type")) {
                    return binary((String) m.get("$binary"), (String) m.get("$type"));
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid Extended JSON value " + m + ": " + ex.getMessage(), ex);
        }
        return m;
    }

    private static Object binary(String base64, String subType) {
        byte[] data = Base64.getDecoder().decode(base64);
        if (subType != null && (subType.equals("04") || subType.equals("4")) && data.length == 16) {
            ByteBuffer bb = ByteBuffer.wrap(data);
            return new UUID(bb.getLong(), bb.getLong());
        }
        return data;
    }

    private static int flags(String opts) {
        int f = 0;
        if (opts == null) {
            return 0;
        }
        for (char c : opts.toCharArray()) {
            switch (c) {
                case 'i': f |= Pattern.CASE_INSENSITIVE; break;
                case 'm': f |= Pattern.MULTILINE; break;
                case 's': f |= Pattern.DOTALL; break;
                case 'x': f |= Pattern.COMMENTS; break;
                default: break;
            }
        }
        return f;
    }

    private static String flagString(int f) {
        StringBuilder b = new StringBuilder();
        if ((f & Pattern.CASE_INSENSITIVE) != 0) b.append('i');
        if ((f & Pattern.MULTILINE) != 0) b.append('m');
        if ((f & Pattern.DOTALL) != 0) b.append('s');
        if ((f & Pattern.COMMENTS) != 0) b.append('x');
        return b.toString();
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, -1, 0);
        return b.toString();
    }

    public static String pretty(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 2, 0);
        return b.toString();
    }

    private static void nl(StringBuilder b, int indent, int level) {
        if (indent > 0) {
            b.append('\n');
            for (int i = 0; i < indent * level; i++) {
                b.append(' ');
            }
        }
    }

    private static void write(StringBuilder b, Object o, int indent, int level) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            str(b, (String) o);
        } else if (o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            b.append(o);
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                b.append("{\"$numberDouble\":\"").append(Double.isNaN(d) ? "NaN" : (d > 0 ? "Infinity" : "-Infinity")).append("\"}");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d).append(".0");
            } else {
                b.append(d);
            }
        } else if (o instanceof BigDecimal) {
            b.append("{\"$numberDecimal\":\"").append(((BigDecimal) o).toPlainString()).append("\"}");
        } else if (o instanceof Number) {
            // e.g. AtomicInteger, BigInteger, Decimal128
            String cls = o.getClass().getSimpleName();
            if (cls.equals("Decimal128")) {
                b.append("{\"$numberDecimal\":\"").append(o).append("\"}");
            } else {
                b.append(o);
            }
        } else if (o instanceof MorphiumId) {
            b.append("{\"$oid\":\"").append(o).append("\"}");
        } else if (o.getClass().getName().equals("org.bson.types.ObjectId")) {
            b.append("{\"$oid\":\"").append(o).append("\"}");
        } else if (o instanceof Date) {
            date(b, ((Date) o).toInstant());
        } else if (o instanceof Instant) {
            date(b, (Instant) o);
        } else if (o instanceof UUID) {
            b.append("{\"$uuid\":\"").append(o).append("\"}");
        } else if (o instanceof byte[]) {
            b.append("{\"$binary\":{\"base64\":\"").append(Base64.getEncoder().encodeToString((byte[]) o))
             .append("\",\"subType\":\"00\"}}");
        } else if (o instanceof Pattern) {
            Pattern p = (Pattern) o;
            b.append("{\"$regularExpression\":{\"pattern\":");
            str(b, p.pattern());
            b.append(",\"options\":\"").append(flagString(p.flags())).append("\"}}");
        } else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                nl(b, indent, level + 1);
                str(b, String.valueOf(e.getKey()));
                b.append(indent > 0 ? ": " : ":");
                write(b, e.getValue(), indent, level + 1);
            }
            nl(b, indent, level);
            b.append('}');
        } else if (o instanceof Collection || o.getClass().isArray()) {
            List<Object> l = new ArrayList<>();
            if (o instanceof Collection) {
                l.addAll((Collection<?>) o);
            } else {
                int n = java.lang.reflect.Array.getLength(o);
                for (int i = 0; i < n; i++) {
                    l.add(java.lang.reflect.Array.get(o, i));
                }
            }
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            boolean first = true;
            for (Object x : l) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                nl(b, indent, level + 1);
                write(b, x, indent, level + 1);
            }
            nl(b, indent, level);
            b.append(']');
        } else {
            String cls = o.getClass().getSimpleName();
            switch (cls) {
                case "MongoTimestamp":
                    b.append("{\"$timestamp\":");
                    str(b, o.toString());
                    b.append('}');
                    break;
                case "MongoMinKey":
                    b.append("{\"$minKey\":1}");
                    break;
                case "MongoMaxKey":
                    b.append("{\"$maxKey\":1}");
                    break;
                default:
                    str(b, o.toString());
            }
        }
    }

    private static void date(StringBuilder b, Instant i) {
        b.append("{\"$date\":\"").append(DateTimeFormatter.ISO_INSTANT.format(i)).append("\"}");
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
