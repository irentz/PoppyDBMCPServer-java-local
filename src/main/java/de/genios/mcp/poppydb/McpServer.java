package de.genios.mcp.poppydb;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP server over stdio (newline-delimited JSON-RPC 2.0), as used by Claude Desktop / Claude Code
 * for local servers. Implemented directly against the spec - no SDK needed.
 */
public final class McpServer {

    static final String NAME = "poppydb-mcp";
    static final String VERSION = "1.0.0";
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final PrintStream out;
    private final Config cfg;
    private final PoppyClient client;
    private final PoppyTools tools;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "mcp-tool");
        t.setDaemon(true);
        return t;
    });

    public McpServer(PrintStream out, Config cfg, PoppyClient client) {
        this.out = out;
        this.cfg = cfg;
        this.client = client;
        this.tools = new PoppyTools(cfg, client);
    }

    public void run() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Object msg;
            try {
                msg = Json.parse(line);
            } catch (Exception e) {
                send(error(null, -32700, "Parse error: " + e.getMessage()));
                continue;
            }
            if (msg instanceof List<?> batch) {
                // JSON-RPC batch (protocol 2025-03-26) - answered sequentially
                List<Object> answers = new ArrayList<>();
                for (Object m : batch) {
                    Map<String, Object> r = handleSync(m);
                    if (r != null) {
                        answers.add(r);
                    }
                }
                if (!answers.isEmpty()) {
                    sendRaw(Json.write(answers));
                }
                continue;
            }
            dispatch(msg);
        }
        pool.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private void dispatch(Object m) {
        if (m instanceof Map<?, ?> map && "tools/call".equals(map.get("method")) && map.containsKey("id")) {
            // tool calls run asynchronously so a slow command does not block ping/cancel
            pool.submit(() -> {
                Map<String, Object> r = handleSync(m);
                if (r != null) {
                    send(r);
                }
            });
            return;
        }
        Map<String, Object> r = handleSync(m);
        if (r != null) {
            send(r);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleSync(Object m) {
        if (!(m instanceof Map)) {
            return error(null, -32600, "Invalid request");
        }
        Map<String, Object> req = (Map<String, Object>) m;
        Object id = req.get("id");
        String method = (String) req.get("method");
        if (method == null) {
            return null; // a response to something we never sent - ignore
        }
        Map<String, Object> params = req.get("params") instanceof Map ? (Map<String, Object>) req.get("params") : Map.of();
        boolean notification = !req.containsKey("id");

        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                case "resources/list" -> Map.of("resources", List.of());
                case "resources/templates/list" -> Map.of("resourceTemplates", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                case "logging/setLevel" -> Map.of();
                default -> {
                    if (method.startsWith("notifications/")) {
                        yield null;
                    }
                    if (notification) {
                        yield null;
                    }
                    throw new RpcException(-32601, "Method not found: " + method);
                }
            };
            if (notification) {
                return null;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", result == null ? Map.of() : result);
            return resp;
        } catch (RpcException e) {
            return notification ? null : error(id, e.code, e.getMessage());
        } catch (Throwable e) {
            return notification ? null : error(id, -32603, "Internal error: " + e);
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = (String) params.get("protocolVersion");
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", version);
        r.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        r.put("serverInfo", Map.of("name", NAME, "title", "PoppyDB", "version", VERSION));
        r.put("instructions", """
                Tools for a PoppyDB server (Morphium's MongoDB wire-compatible in-memory database, \
                optimized for messaging). Connected to %s, default database '%s'. \
                Write tools: %s. Operational tools: %s. \
                Filters and documents use MongoDB query syntax; Extended JSON ($oid, $date, $numberLong) \
                is accepted and returned. Admin tools accept 'node' (host:port) to ask a single replica \
                set member directly; use cluster_overview first when investigating replication problems."""
                .formatted(cfg.hosts, cfg.database, cfg.allowWrite ? "enabled" : "disabled (--allow-write)",
                        cfg.allowOps ? "enabled" : "disabled (--allow-ops)"));
        return r;
    }

    private Map<String, Object> listTools() {
        List<Object> l = new ArrayList<>();
        for (Tool t : tools.all().values()) {
            l.add(t.describe());
        }
        return Map.of("tools", l);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) throws RpcException {
        String name = (String) params.get("name");
        Tool t = name == null ? null : tools.all().get(name);
        if (t == null) {
            throw new RpcException(-32602, "Unknown tool: " + name
                                           + (name != null && name.matches(".*(insert|update|delete|drop|create).*")
                                              ? " (write tools need --allow-write)" : "")
                                           + (name != null && name.matches(".*(step|freeze|dump_now|kill|shutdown|log_level).*")
                                              ? " (operational tools need --allow-ops)" : ""));
        }
        Map<String, Object> args = params.get("arguments") instanceof Map
                                   ? (Map<String, Object>) params.get("arguments") : Map.of();
        long start = System.currentTimeMillis();
        try {
            Object result = t.handler.handle(new Tool.Args(args));
            String text = Json.pretty(result);
            if (text.length() > cfg.maxOutputChars) {
                text = text.substring(0, cfg.maxOutputChars)
                       + "\n... [output truncated after " + cfg.maxOutputChars + " characters - narrow the query]";
            }
            System.err.println("[poppydb-mcp] " + name + " ok (" + (System.currentTimeMillis() - start) + "ms)");
            return toolResult(text, false);
        } catch (SecurityException | IllegalArgumentException e) {
            return toolResult("Not executed: " + e.getMessage(), true);
        } catch (Throwable e) {
            // Throwable, not Exception: a LinkageError etc. must still produce an answer,
            // otherwise the client waits forever for this request id
            System.err.println("[poppydb-mcp] " + name + " failed: " + e);
            if (isConnectionProblem(e)) {
                client.reset();
            }
            String msg = describe(e);
            if (msg.contains("was removed while waiting")) {
                msg += cfg.user != null
                       ? " (likely cause: server unreachable or authentication failed - check host/port, proxy, user, password, authSource)"
                       : " (likely cause: server unreachable - check host/port and proxy)";
            }
            return toolResult("Error from PoppyDB (" + cfg.hosts + "): " + msg, true);
        }
    }

    private static boolean isConnectionProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("Network") || n.contains("Socket") || n.contains("Connect") || n.contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null && b.length() < 1000; t = t.getCause()) {
            if (b.length() > 0) {
                b.append(" <- ");
            }
            b.append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            if (t.getCause() == t) {
                break;
            }
        }
        return b.toString();
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", List.of(Map.of("type", "text", "text", text)));
        r.put("isError", isError);
        return r;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", Map.of("code", code, "message", message));
        return r;
    }

    private void send(Map<String, Object> msg) {
        sendRaw(Json.write(msg));
    }

    private synchronized void sendRaw(String s) {
        out.print(s);
        out.print('\n');
        out.flush();
    }

    static final class RpcException extends Exception {
        final int code;

        RpcException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
