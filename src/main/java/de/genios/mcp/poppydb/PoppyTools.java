package de.genios.mcp.poppydb;

import de.genios.mcp.poppydb.Tool.Args;
import de.genios.mcp.poppydb.Tool.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** All tools offered by the server. Write/ops tools are only registered when enabled. */
public final class PoppyTools {

    private static final String DB = "Database name (default: the configured default database)";
    private static final String COLL = "Collection name";
    private static final String NODE = "Optional host:port of a single replica set member to ask directly "
                                       + "(default: the current primary)";
    private static final String FILTER = "Query filter as JSON object (MongoDB syntax, Extended JSON like "
                                         + "{\"$oid\":\"...\"} or {\"$date\":\"2026-01-01T00:00:00Z\"} allowed)";

    // ---- command classification for run_command
    private static final Set<String> READ_COMMANDS = Set.of(
            "hello", "ismaster", "ping", "buildinfo", "hostinfo", "listdatabases", "listcollections",
            "listindexes", "find", "count", "distinct", "aggregate", "collstats", "dbstats", "replsetgetstatus",
            "replsetgetconfig", "getlog", "getmessagingstats", "dumpstatus", "currentop", "getcmdlineopts",
            "getparameter", "connectionstatus", "serverstatus", "listcommands", "startupwarnings", "whatsmyuri",
            "explain", "poppyrestorestatus", "getfreemonitoringstatus");
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "insert", "update", "delete", "findandmodify", "createindexes", "dropindexes", "drop", "create",
            "renamecollection", "dropdatabase");
    private static final Set<String> OPS_COMMANDS = Set.of(
            "replsetstepdown", "replsetfreeze", "dumpnow", "killop", "setparameter", "shutdown",
            "poppyacceptpartialrestore");

    private final Config cfg;
    private final PoppyClient client;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public PoppyTools(Config cfg, PoppyClient client) {
        this.cfg = cfg;
        this.client = client;
        registerRead();
        registerAdmin();
        if (cfg.allowWrite) {
            registerWrite();
        }
        if (cfg.allowOps) {
            registerOps();
        }
    }

    public Map<String, Tool> all() {
        return tools;
    }

    private Tool add(Tool t) {
        tools.put(t.name, t);
        return t;
    }

    private String db(Args a) {
        return a.str("database", cfg.database);
    }

    private static Map<String, Object> doc(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    private int limit(Args a, String key) {
        int l = a.integer(key, Math.min(20, cfg.maxDocs));
        if (l <= 0 || l > cfg.maxDocs) {
            l = cfg.maxDocs;
        }
        return l;
    }

    private static Map<String, Object> cursorResult(PoppyClient.CursorResult r, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("returned", r.docs().size());
        m.put("truncated", r.truncated());
        if (r.truncated()) {
            m.put("hint", hint);
        }
        m.put("documents", r.docs());
        return m;
    }

    private static Map<String, Object> clean(Map<String, Object> reply) {
        Map<String, Object> m = new LinkedHashMap<>(reply);
        m.remove("$clusterTime");
        m.remove("operationTime");
        return m;
    }

    // ================================================================ data (read)

    private void registerRead() {
        add(new Tool("list_databases", "List databases", Level.READ,
                "Lists all databases on the PoppyDB server."))
                .handler(a -> {
                    Map<String, Object> r = client.command("admin", doc("listDatabases", 1), null);
                    List<Object> names = new ArrayList<>();
                    Object dbs = r.get("databases");
                    if (dbs instanceof List<?> l) {
                        for (Object o : l) {
                            names.add(o instanceof Map<?, ?> m ? m.get("name") : o);
                        }
                    }
                    return doc("databases", names);
                });

        add(new Tool("list_collections", "List collections", Level.READ,
                "Lists the collections of a database (name and type)."))
                .param("database", "string", DB, false)
                .param("filter", "object", "Optional listCollections filter, e.g. {\"name\":{\"$regex\":\"^msg\"}}", false)
                .handler(a -> {
                    Map<String, Object> cmd = doc("listCollections", 1, "nameOnly", true, "filter", a.obj("filter", null),
                            "cursor", doc());
                    var r = client.cursorCommand(db(a), cmd, 10_000, null);
                    List<Object> out = new ArrayList<>();
                    for (Map<String, Object> d : r.docs()) {
                        out.add(doc("name", d.get("name"), "type", d.get("type")));
                    }
                    return doc("database", db(a), "collections", out);
                });

        add(new Tool("find", "Find documents", Level.READ,
                "Queries documents of a collection. Returns at most 'limit' documents (server cap: "
                + cfg.maxDocs + ")."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("filter", "object", FILTER, false)
                .param("projection", "object", "Projection, e.g. {\"name\":1,\"_id\":0}", false)
                .param("sort", "object", "Sort specification, e.g. {\"timestamp\":-1}", false)
                .param("skip", "integer", "Number of documents to skip", false)
                .param("limit", "integer", "Max documents to return (default 20)", false)
                .handler(a -> {
                    int limit = limit(a, "limit");
                    Map<String, Object> cmd = doc("find", a.reqStr("collection"),
                            "filter", a.obj("filter", doc()),
                            "projection", a.obj("projection", null),
                            "sort", a.obj("sort", null),
                            "skip", a.has("skip") ? a.integer("skip", 0) : null,
                            "limit", limit + 1,
                            "batchSize", limit + 1);
                    var r = client.cursorCommand(db(a), cmd, limit, null);
                    return cursorResult(r, "More documents match - narrow the filter, use skip, or call count.");
                });

        add(new Tool("count", "Count documents", Level.READ,
                "Counts the documents matching a filter."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("filter", "object", FILTER, false)
                .handler(a -> {
                    Map<String, Object> r = client.command(db(a),
                            doc("count", a.reqStr("collection"), "query", a.obj("filter", doc())), null);
                    return doc("count", r.get("n"));
                });

        add(new Tool("aggregate", "Run aggregation", Level.READ,
                "Runs an aggregation pipeline and returns the result documents. $out/$merge stages require "
                + "write mode."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("pipeline", "object[]", "Aggregation pipeline (array of stage objects)", true)
                .param("limit", "integer", "Max result documents to return (default 20)", false)
                .handler(a -> {
                    List<Object> pipeline = a.list("pipeline");
                    if (pipeline == null) {
                        throw new IllegalArgumentException("Parameter 'pipeline' is required");
                    }
                    for (Object st : pipeline) {
                        if (st instanceof Map<?, ?> m && (m.containsKey("$out") || m.containsKey("$merge"))
                                && !cfg.allowWrite) {
                            throw new SecurityException("$out/$merge write data - start the server with --allow-write");
                        }
                    }
                    int limit = limit(a, "limit");
                    Map<String, Object> cmd = doc("aggregate", a.reqStr("collection"), "pipeline", pipeline,
                            "cursor", doc("batchSize", limit + 1));
                    var r = client.cursorCommand(db(a), cmd, limit, null);
                    return cursorResult(r, "More results - add a $limit stage or aggregate further.");
                });

        add(new Tool("distinct", "Distinct values", Level.READ,
                "Returns the distinct values of a field."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("key", "string", "Field name (dot notation allowed)", true)
                .param("filter", "object", FILTER, false)
                .handler(a -> {
                    Map<String, Object> r = client.command(db(a), doc("distinct", a.reqStr("collection"),
                            "key", a.reqStr("key"), "query", a.obj("filter", doc())), null);
                    Object vals = r.get("values");
                    int n = vals instanceof List<?> l ? l.size() : 0;
                    if (vals instanceof List<?> l && l.size() > cfg.maxDocs) {
                        return doc("count", n, "truncated", true, "values", l.subList(0, cfg.maxDocs));
                    }
                    return doc("count", n, "values", vals);
                });

        add(new Tool("list_indexes", "List indexes", Level.READ,
                "Lists the indexes of a collection."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .handler(a -> client.cursorCommand(db(a), doc("listIndexes", a.reqStr("collection"), "cursor", doc()),
                        1000, null).docs());

        add(new Tool("collection_stats", "Collection statistics", Level.READ,
                "Returns collStats for a collection (document count, size, indexes) if the server supports it."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .handler(a -> clean(client.command(db(a), doc("collStats", a.reqStr("collection")), null)));

        add(new Tool("db_stats", "Database statistics", Level.READ,
                "Returns dbStats for a database if the server supports it."))
                .param("database", "string", DB, false)
                .handler(a -> clean(client.command(db(a), doc("dbStats", 1), null)));
    }

    // ================================================================ admin / status (read)

    private void registerAdmin() {
        add(new Tool("server_info", "Server info", Level.READ,
                "Overview of one PoppyDB node: hello (role, replica set, hosts), buildInfo, hostInfo and "
                + "connectionStatus."))
                .param("node", "string", NODE, false)
                .handler(a -> {
                    String node = a.str("node", null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("hello", safe(() -> clean(client.command("admin", doc("hello", 1), node))));
                    out.put("buildInfo", safe(() -> clean(client.command("admin", doc("buildInfo", 1), node))));
                    out.put("hostInfo", safe(() -> clean(client.command("admin", doc("hostInfo", 1), node))));
                    out.put("connectionStatus", safe(() -> clean(client.command("admin", doc("connectionStatus", 1), node))));
                    return out;
                });

        add(new Tool("cluster_overview", "Cluster overview", Level.READ,
                "Asks every known replica set member directly for hello and replSetGetStatus and summarises "
                + "role, primary each node believes in, term and reachability - useful to spot split-brain, "
                + "stale primaries or nodes in initial sync."))
                .handler(a -> {
                    List<String> hosts = new ArrayList<>(client.knownHosts());
                    // add members reported by the primary
                    try {
                        Map<String, Object> hello = client.command("admin", doc("hello", 1), null);
                        if (hello.get("hosts") instanceof List<?> l) {
                            for (Object h : l) {
                                if (!hosts.contains(String.valueOf(h))) {
                                    hosts.add(String.valueOf(h));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // primary unreachable - just ask the seed
                    }
                    List<Object> nodes = new ArrayList<>();
                    for (String h : hosts) {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("node", h);
                        try {
                            Map<String, Object> hello = client.command("admin", doc("hello", 1), h);
                            n.put("reachable", true);
                            n.put("isWritablePrimary", hello.getOrDefault("isWritablePrimary", hello.get("ismaster")));
                            n.put("secondary", hello.get("secondary"));
                            n.put("setName", hello.get("setName"));
                            n.put("primary", hello.get("primary"));
                            n.put("me", hello.get("me"));
                            n.put("hosts", hello.get("hosts"));
                            Object st = safe(() -> clean(client.command("admin", doc("replSetGetStatus", 1), h)));
                            if (st instanceof Map<?, ?> sm) {
                                for (String k : List.of("myState", "stateStr", "term", "syncSourceHost",
                                        "initialSyncStatus", "electionState")) {
                                    if (sm.get(k) != null) {
                                        n.put(k, sm.get(k));
                                    }
                                }
                                if (sm.get("error") != null) {
                                    n.put("replSetGetStatusError", sm.get("error"));
                                }
                            }
                        } catch (Exception e) {
                            n.put("reachable", false);
                            n.put("error", e.getMessage());
                        }
                        nodes.add(n);
                    }
                    return doc("nodes", nodes);
                });

        add(new Tool("replset_status", "Replica set status", Level.READ,
                "Runs replSetGetStatus (members, states, terms, optimes, initial sync) and optionally "
                + "replSetGetConfig on one node."))
                .param("node", "string", NODE, false)
                .param("includeConfig", "boolean", "Also return replSetGetConfig", false)
                .handler(a -> {
                    String node = a.str("node", null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", clean(client.command("admin", doc("replSetGetStatus", 1), node)));
                    if (a.bool("includeConfig", false)) {
                        out.put("config", safe(() -> clean(client.command("admin", doc("replSetGetConfig", 1), node))));
                    }
                    return out;
                });

        add(new Tool("restore_status", "Restore status", Level.READ,
                "PoppyDB-specific: the node's dump-restore finding (poppyRestoreStatus) - relevant when a "
                + "leaderless set refuses to elect because of a partial restore."))
                .param("node", "string", NODE, false)
                .handler(a -> clean(client.command("admin", doc("poppyRestoreStatus", 1), a.str("node", null))));

        add(new Tool("get_log", "Server log", Level.READ,
                "Reads the in-memory log ring buffer of a node (getLog 'global') or its startup warnings. "
                + "Returns the last 'limit' lines, optionally filtered by a substring."))
                .param("node", "string", NODE, false)
                .param("log", "string", "'global' (default) or 'startupWarnings'", false)
                .param("contains", "string", "Only lines containing this text (case-insensitive)", false)
                .param("limit", "integer", "Number of most recent lines (default 100)", false)
                .handler(a -> {
                    String name = a.str("log", "global");
                    Map<String, Object> r = client.command("admin", doc("getLog", name), a.str("node", null));
                    List<Object> lines = new ArrayList<>();
                    if (r.get("log") instanceof List<?> l) {
                        lines.addAll(l);
                    }
                    String needle = a.str("contains", null);
                    if (needle != null) {
                        String n = needle.toLowerCase(Locale.ROOT);
                        lines.removeIf(x -> !String.valueOf(x).toLowerCase(Locale.ROOT).contains(n));
                    }
                    int limit = Math.max(1, a.integer("limit", 100));
                    int matched = lines.size();
                    if (lines.size() > limit) {
                        lines = new ArrayList<>(lines.subList(lines.size() - limit, lines.size()));
                    }
                    return doc("log", name, "totalLinesWritten", r.get("totalLinesWritten"), "matched", matched,
                            "returned", lines.size(), "lines", lines);
                });

        add(new Tool("messaging_stats", "Messaging stats", Level.READ,
                "PoppyDB-specific: statistics of the messaging optimizer (registered messaging collections, "
                + "subscribers, notifications) - getMessagingStats."))
                .param("node", "string", NODE, false)
                .handler(a -> clean(client.command("admin", doc("getMessagingStats", 1), a.str("node", null))));

        add(new Tool("dump_status", "Persistence status", Level.READ,
                "PoppyDB-specific: state of the periodic dump persistence (dumpStatus) - dump dir, last dump, "
                + "interval, errors."))
                .param("node", "string", NODE, false)
                .handler(a -> clean(client.command("admin", doc("dumpStatus", 1), a.str("node", null))));

        add(new Tool("current_op", "Current operations", Level.READ,
                "Lists operations currently running on a node (currentOp)."))
                .param("node", "string", NODE, false)
                .handler(a -> clean(client.command("admin", doc("currentOp", 1), a.str("node", null))));

        add(new Tool("server_options", "Server options", Level.READ,
                "Command line / config file options a node was started with (getCmdLineOpts) plus runtime "
                + "parameters (getParameter '*')."))
                .param("node", "string", NODE, false)
                .handler(a -> {
                    String node = a.str("node", null);
                    return doc("cmdLineOpts", safe(() -> clean(client.command("admin", doc("getCmdLineOpts", 1), node))),
                            "parameters", safe(() -> clean(client.command("admin", doc("getParameter", "*"), node))));
                });

        add(new Tool("run_command", "Run raw command", Level.READ,
                "Runs an arbitrary database command document, e.g. {\"listCommands\":1}. Read commands are "
                + "always allowed; write commands need --allow-write, operational commands (stepDown, "
                + "freeze, shutdown, ...) and unknown commands need --allow-ops. Replication-internal "
                + "commands are never allowed."))
                .param("database", "string", "Database to run the command against (default: admin)", false)
                .param("command", "object", "Command document; the first key is the command name", true)
                .param("node", "string", NODE, false)
                .param("limit", "integer", "Max documents for cursor results (default 20)", false)
                .handler(a -> {
                    Map<String, Object> cmd = a.reqObj("command");
                    if (cmd.isEmpty()) {
                        throw new IllegalArgumentException("Empty command");
                    }
                    String name = cmd.keySet().iterator().next();
                    checkAllowed(name, cmd);
                    String db = a.str("database", "admin");
                    String node = a.str("node", null);
                    String lc = name.toLowerCase(Locale.ROOT);
                    if (Set.of("find", "aggregate", "listcollections", "listindexes").contains(lc)) {
                        if (lc.equals("aggregate") && !cmd.containsKey("cursor")) {
                            cmd.put("cursor", doc());
                        }
                        return cursorResult(client.cursorCommand(db, cmd, limit(a, "limit"), node),
                                "More results available.");
                    }
                    return clean(client.command(db, cmd, node));
                });
    }

    private void checkAllowed(String name, Map<String, Object> cmd) {
        String lc = name.toLowerCase(Locale.ROOT);
        if (READ_COMMANDS.contains(lc)) {
            if (lc.equals("aggregate") && cmd.get("pipeline") instanceof List<?> p) {
                for (Object st : p) {
                    if (st instanceof Map<?, ?> m && (m.containsKey("$out") || m.containsKey("$merge")) && !cfg.allowWrite) {
                        throw new SecurityException("$out/$merge need --allow-write");
                    }
                }
            }
            return;
        }
        if (WRITE_COMMANDS.contains(lc)) {
            if (!cfg.allowWrite) {
                throw new SecurityException("'" + name + "' modifies data - start the server with --allow-write");
            }
            return;
        }
        if (OPS_COMMANDS.contains(lc)) {
            if (!cfg.allowOps) {
                throw new SecurityException("'" + name + "' is an operational command - start the server with --allow-ops");
            }
            return;
        }
        if (Set.of("appendentries", "requestvote", "replsetprogress", "poppycursoralive", "registermessagingcollection",
                "unregistermessagingsubscriber", "saslstart", "saslcontinue", "authenticate", "getmore", "killcursors",
                "startsession", "endsessions", "refreshsessions", "committransaction", "aborttransaction",
                "createuser", "dropuser", "updateuser").contains(lc)) {
            throw new SecurityException("'" + name + "' is a protocol-internal or user-management command and not allowed");
        }
        if (!cfg.allowOps) {
            throw new SecurityException("Unknown command '" + name + "' - only allowed with --allow-ops");
        }
    }

    @FunctionalInterface
    private interface Call {
        Object call() throws Exception;
    }

    private static Object safe(Call c) {
        try {
            return c.call();
        } catch (Exception e) {
            return doc("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ================================================================ write

    private void registerWrite() {
        add(new Tool("insert_documents", "Insert documents", Level.WRITE,
                "Inserts one or more documents into a collection."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("documents", "object[]", "Documents to insert", true)
                .handler(a -> {
                    List<Object> docs = a.list("documents");
                    if (docs == null || docs.isEmpty()) {
                        throw new IllegalArgumentException("'documents' must contain at least one document");
                    }
                    Map<String, Object> r = client.command(db(a), doc("insert", a.reqStr("collection"),
                            "documents", docs, "ordered", true), null);
                    return writeResult(r);
                });

        add(new Tool("update_documents", "Update documents", Level.WRITE,
                "Updates documents matching a filter with an update document ($set, $inc, ...) or pipeline."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("filter", "object", FILTER, true)
                .param("update", "any", "Update document (e.g. {\"$set\":{\"a\":1}}) or aggregation pipeline array", true)
                .param("multi", "boolean", "Update all matching documents (default false = first match only)", false)
                .param("upsert", "boolean", "Insert if nothing matches (default false)", false)
                .handler(a -> {
                    Object upd = a.raw("update");
                    if (upd instanceof String s) {
                        upd = Json.parse(s);
                    }
                    if (upd == null) {
                        throw new IllegalArgumentException("Parameter 'update' is required");
                    }
                    Map<String, Object> filter = a.reqObj("filter");
                    boolean multi = a.bool("multi", false);
                    if (multi && filter.isEmpty()) {
                        throw new IllegalArgumentException("Refusing a multi update with an empty filter - "
                                                           + "use run_command if you really mean all documents");
                    }
                    Map<String, Object> r = client.command(db(a), doc("update", a.reqStr("collection"),
                            "updates", List.of(doc("q", filter, "u", upd, "multi", multi, "upsert", a.bool("upsert", false))),
                            "ordered", true), null);
                    return writeResult(r);
                });

        add(new Tool("delete_documents", "Delete documents", Level.WRITE,
                "Deletes documents matching a filter. An empty filter with multi=true requires confirm=true."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("filter", "object", FILTER, true)
                .param("multi", "boolean", "Delete all matching documents (default false = first match only)", false)
                .param("confirm", "boolean", "Required to delete ALL documents (empty filter + multi)", false)
                .destructive()
                .handler(a -> {
                    Map<String, Object> filter = a.reqObj("filter");
                    boolean multi = a.bool("multi", false);
                    if (multi && filter.isEmpty() && !a.bool("confirm", false)) {
                        throw new IllegalArgumentException("This would delete ALL documents - repeat with confirm=true");
                    }
                    Map<String, Object> r = client.command(db(a), doc("delete", a.reqStr("collection"),
                            "deletes", List.of(doc("q", filter, "limit", multi ? 0 : 1)), "ordered", true), null);
                    return writeResult(r);
                });

        add(new Tool("create_index", "Create index", Level.WRITE,
                "Creates an index on a collection."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("keys", "object", "Index keys, e.g. {\"user\":1,\"ts\":-1}", true)
                .param("name", "string", "Index name (default: generated)", false)
                .param("unique", "boolean", "Unique index", false)
                .param("expireAfterSeconds", "integer", "TTL in seconds (TTL index)", false)
                .idempotent()
                .handler(a -> {
                    Map<String, Object> keys = a.reqObj("keys");
                    String name = a.str("name", null);
                    if (name == null) {
                        StringBuilder b = new StringBuilder();
                        keys.forEach((k, v) -> b.append(b.length() == 0 ? "" : "_").append(k).append('_').append(v));
                        name = b.toString();
                    }
                    Map<String, Object> idx = doc("key", keys, "name", name,
                            "unique", a.has("unique") ? a.bool("unique", false) : null,
                            "expireAfterSeconds", a.has("expireAfterSeconds") ? a.integer("expireAfterSeconds", 0) : null);
                    return clean(client.command(db(a), doc("createIndexes", a.reqStr("collection"),
                            "indexes", List.of(idx)), null));
                });

        add(new Tool("drop_index", "Drop index", Level.WRITE,
                "Drops an index by name."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("name", "string", "Index name (see list_indexes)", true)
                .destructive()
                .handler(a -> clean(client.command(db(a), doc("dropIndexes", a.reqStr("collection"),
                        "index", a.reqStr("name")), null)));

        add(new Tool("drop_collection", "Drop collection", Level.WRITE,
                "Drops a whole collection including its indexes. 'confirm' must repeat the collection name."))
                .param("database", "string", DB, false)
                .param("collection", "string", COLL, true)
                .param("confirm", "string", "Must equal the collection name", true)
                .destructive()
                .handler(a -> {
                    String coll = a.reqStr("collection");
                    if (!coll.equals(a.str("confirm", ""))) {
                        throw new IllegalArgumentException("'confirm' must equal the collection name '" + coll + "'");
                    }
                    return clean(client.command(db(a), doc("drop", coll), null));
                });
    }

    private static Map<String, Object> writeResult(Map<String, Object> r) {
        Map<String, Object> m = clean(r);
        if (m.get("writeErrors") != null || m.get("writeConcernError") != null) {
            // surfaces as isError=true so the model does not mistake a failed write for success
            throw new IllegalStateException("write failed (partially) - " + Json.write(m));
        }
        return m;
    }

    // ================================================================ operations

    private void registerOps() {
        add(new Tool("replset_step_down", "Step down primary", Level.OPS,
                "Makes the primary step down (replSetStepDown) so another member is elected."))
                .param("node", "string", NODE, false)
                .param("stepDownSecs", "integer", "Seconds the node will not seek re-election (default 60)", false)
                .param("secondaryCatchUpPeriodSecs", "integer", "Seconds to wait for a secondary to catch up", false)
                .param("force", "boolean", "Step down even without a caught-up secondary", false)
                .destructive()
                .handler(a -> clean(client.command("admin", doc("replSetStepDown", a.integer("stepDownSecs", 60),
                        "secondaryCatchUpPeriodSecs", a.has("secondaryCatchUpPeriodSecs")
                                ? a.integer("secondaryCatchUpPeriodSecs", 10) : null,
                        "force", a.has("force") ? a.bool("force", false) : null), a.str("node", null))));

        add(new Tool("replset_freeze", "Freeze member", Level.OPS,
                "Prevents a member from seeking election for N seconds (replSetFreeze); 0 unfreezes."))
                .param("node", "string", "host:port of the member to freeze", true)
                .param("seconds", "integer", "Freeze duration in seconds, 0 = unfreeze", true)
                .handler(a -> clean(client.command("admin", doc("replSetFreeze", a.integer("seconds", 0)),
                        a.reqStr("node"))));

        add(new Tool("dump_now", "Dump now", Level.OPS,
                "PoppyDB-specific: triggers an immediate persistence dump on a node (dumpNow)."))
                .param("node", "string", NODE, false)
                .handler(a -> clean(client.command("admin", doc("dumpNow", 1), a.str("node", null))));

        add(new Tool("kill_op", "Kill operation", Level.OPS,
                "Kills a running operation by opid (see current_op)."))
                .param("node", "string", NODE, false)
                .param("op", "integer", "Operation id", true)
                .destructive()
                .handler(a -> {
                    if (!a.has("op")) {
                        throw new IllegalArgumentException("Parameter 'op' is required");
                    }
                    Object op = a.raw("op");
                    long id = op instanceof Number n ? n.longValue() : Long.parseLong(op.toString());
                    return clean(client.command("admin", doc("killOp", 1, "op", id), a.str("node", null)));
                });

        add(new Tool("set_log_level", "Set log level", Level.OPS,
                "Changes a node's log verbosity at runtime (setParameter logLevel, 0=INFO, 1-2=DEBUG, 3-5=TRACE)."))
                .param("node", "string", NODE, false)
                .param("level", "integer", "0..5", true)
                .idempotent()
                .handler(a -> clean(client.command("admin", doc("setParameter", 1, "logLevel", a.integer("level", 0)),
                        a.str("node", null))));

        add(new Tool("shutdown_node", "Shut down node", Level.OPS,
                "Shuts a PoppyDB node down. Data not dumped is LOST (in-memory server). 'confirm' must repeat "
                + "the node address."))
                .param("node", "string", "host:port of the node to shut down", true)
                .param("confirm", "string", "Must equal 'node'", true)
                .param("force", "boolean", "Shut down even a primary without caught-up secondaries", false)
                .param("timeoutSecs", "integer", "Seconds to wait for secondaries (default 15)", false)
                .destructive()
                .handler(a -> {
                    String node = a.reqStr("node");
                    if (!node.equals(a.str("confirm", ""))) {
                        throw new IllegalArgumentException("'confirm' must equal the node address '" + node + "'");
                    }
                    try {
                        return clean(client.command("admin", doc("shutdown", 1,
                                "force", a.has("force") ? a.bool("force", false) : null,
                                "timeoutSecs", a.has("timeoutSecs") ? a.integer("timeoutSecs", 15) : null), node));
                    } catch (Exception e) {
                        // the node closes the socket while stopping - that is the expected outcome
                        return doc("ok", 1, "info", "Shutdown sent; connection closed by the node (" + e.getMessage() + ")");
                    }
                });
    }
}
