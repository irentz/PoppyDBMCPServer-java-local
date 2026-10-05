package de.genios.mcp.poppydb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One MCP tool: metadata, JSON schema and handler. */
public final class Tool {

    /** Permission level a tool requires. */
    public enum Level { READ, WRITE, OPS }

    @FunctionalInterface
    public interface Handler {
        Object handle(Args args) throws Exception;
    }

    final String name;
    final String title;
    final String description;
    final Level level;
    final Map<String, Object> properties = new LinkedHashMap<>();
    final List<String> required = new ArrayList<>();
    Handler handler;
    boolean destructive;
    boolean idempotent;

    Tool(String name, String title, Level level, String description) {
        this.name = name;
        this.title = title;
        this.level = level;
        this.description = description;
        this.idempotent = level == Level.READ;
    }

    Tool param(String name, String type, String description, boolean required) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (type.endsWith("[]")) {
            p.put("type", "array");
            String item = type.substring(0, type.length() - 2);
            p.put("items", item.equals("any") ? Map.of() : Map.of("type", item));
        } else if (!type.equals("any")) {
            p.put("type", type);
        }
        p.put("description", description);
        properties.put(name, p);
        if (required) {
            this.required.add(name);
        }
        return this;
    }

    Tool destructive() {
        this.destructive = true;
        return this;
    }

    Tool idempotent() {
        this.idempotent = true;
        return this;
    }

    Tool handler(Handler h) {
        this.handler = h;
        return this;
    }

    Map<String, Object> describe() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);

        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("title", title);
        ann.put("readOnlyHint", level == Level.READ);
        ann.put("destructiveHint", level != Level.READ && destructive);
        ann.put("idempotentHint", idempotent);
        ann.put("openWorldHint", false);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("title", title);
        d.put("description", description);
        d.put("inputSchema", schema);
        d.put("annotations", ann);
        return d;
    }

    /** Typed access to tool arguments. */
    public static final class Args {
        private final Map<String, Object> m;

        Args(Map<String, Object> m) {
            this.m = m == null ? Map.of() : m;
        }

        boolean has(String k) {
            return m.get(k) != null;
        }

        Object raw(String k) {
            return m.get(k);
        }

        String str(String k, String def) {
            Object v = m.get(k);
            if (v == null) {
                return def;
            }
            String s = v.toString();
            return s.isBlank() ? def : s;
        }

        String reqStr(String k) {
            String s = str(k, null);
            if (s == null) {
                throw new IllegalArgumentException("Parameter '" + k + "' is required");
            }
            return s;
        }

        int integer(String k, int def) {
            Object v = m.get(k);
            if (v == null) {
                return def;
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(v.toString().trim());
        }

        boolean bool(String k, boolean def) {
            Object v = m.get(k);
            if (v == null) {
                return def;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(v.toString().trim());
        }

        /** Object parameter; also accepts a JSON string (some clients stringify nested objects). */
        @SuppressWarnings("unchecked")
        Map<String, Object> obj(String k, Map<String, Object> def) {
            Object v = m.get(k);
            if (v == null) {
                return def;
            }
            if (v instanceof String s) {
                if (s.isBlank()) {
                    return def;
                }
                v = Json.parse(s);
            }
            if (!(v instanceof Map)) {
                throw new IllegalArgumentException("Parameter '" + k + "' must be a JSON object");
            }
            return new LinkedHashMap<>((Map<String, Object>) v);
        }

        Map<String, Object> reqObj(String k) {
            Map<String, Object> o = obj(k, null);
            if (o == null) {
                throw new IllegalArgumentException("Parameter '" + k + "' is required");
            }
            return o;
        }

        /** Array parameter; also accepts a JSON string. */
        @SuppressWarnings("unchecked")
        List<Object> list(String k) {
            Object v = m.get(k);
            if (v == null) {
                return null;
            }
            if (v instanceof String s) {
                v = Json.parse(s);
            }
            if (v instanceof Map) {
                return new ArrayList<>(List.of(v));
            }
            if (!(v instanceof List)) {
                throw new IllegalArgumentException("Parameter '" + k + "' must be a JSON array");
            }
            return new ArrayList<>((List<Object>) v);
        }
    }
}
