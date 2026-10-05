package de.genios.mcp.poppydb;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server configuration. Every option can be given as command line argument or environment variable
 * (command line wins). Secrets should be passed via environment ({@code POPPYDB_PASSWORD}) or a file
 * ({@code --password-file}) so they do not show up in {@code ps}.
 */
public final class Config {

    public List<String> hosts = new ArrayList<>(List.of("localhost:17017"));
    public String database = "test";
    public String user;
    public String password;
    public String authDb = "admin";
    public String replicaSet;
    public boolean tls;
    public boolean allowWrite;
    public boolean allowOps;
    public int maxDocs = 100;
    public int maxOutputChars = 100_000;
    public int maxWaitMs = 15_000;
    public int connectTimeoutMs = 5_000;

    public static final String USAGE = """
            poppydb-mcp - MCP server (stdio) for PoppyDB / MongoDB-wire compatible servers

            Options (environment variable in brackets; command line wins):
              --uri <mongodb://...>     Connection string [POPPYDB_URI]
                                        mongodb://[user:pw@]host1:port1,host2:port2/[db]?authSource=admin&replicaSet=rs&tls=true
              --hosts <h:p,h:p>         Host seed (default localhost:17017) [POPPYDB_HOSTS]
              --database <name>         Default database (default: test) [POPPYDB_DATABASE]
              --user <name>             SCRAM user [POPPYDB_USER]
              --password <pw>           SCRAM password - prefer the env var [POPPYDB_PASSWORD]
              --password-file <path>    Read password from file [POPPYDB_PASSWORD_FILE]
              --auth-db <name>          Authentication database (default: admin) [POPPYDB_AUTH_DB]
              --replica-set <name>      Required replica set name (optional) [POPPYDB_REPLICA_SET]
              --tls                     Use TLS [POPPYDB_TLS=true]
              --allow-write             Enable insert/update/delete/index/drop tools [POPPYDB_ALLOW_WRITE=true]
              --allow-ops               Enable operational tools (stepDown, freeze, dumpNow, killOp,
                                        setParameter, shutdown) [POPPYDB_ALLOW_OPS=true]
              --max-docs <n>            Max documents returned per call (default 100) [POPPYDB_MAX_DOCS]
              --max-output-chars <n>    Truncate tool output after n characters (default 100000)
                                        [POPPYDB_MAX_OUTPUT_CHARS]
              --max-wait-ms <n>         Command timeout in ms (default 15000) [POPPYDB_MAX_WAIT_MS]
              --help                    Show this help

            SOCKS proxy: start the JVM with -DsocksProxyHost=localhost -DsocksProxyPort=5555
            """;

    public static Config load(String[] args, Map<String, String> env) throws Exception {
        Config c = new Config();

        // 1. environment
        if (env.get("POPPYDB_URI") != null) {
            c.applyUri(env.get("POPPYDB_URI"));
        }
        if (env.get("POPPYDB_HOSTS") != null) {
            c.hosts = split(env.get("POPPYDB_HOSTS"));
        }
        c.database = env.getOrDefault("POPPYDB_DATABASE", c.database);
        c.user = env.getOrDefault("POPPYDB_USER", c.user);
        c.password = env.getOrDefault("POPPYDB_PASSWORD", c.password);
        if (env.get("POPPYDB_PASSWORD_FILE") != null) {
            c.password = readSecret(env.get("POPPYDB_PASSWORD_FILE"));
        }
        c.authDb = env.getOrDefault("POPPYDB_AUTH_DB", c.authDb);
        c.replicaSet = env.getOrDefault("POPPYDB_REPLICA_SET", c.replicaSet);
        c.tls = bool(env.get("POPPYDB_TLS"), c.tls);
        c.allowWrite = bool(env.get("POPPYDB_ALLOW_WRITE"), c.allowWrite);
        c.allowOps = bool(env.get("POPPYDB_ALLOW_OPS"), c.allowOps);
        c.maxDocs = integer(env.get("POPPYDB_MAX_DOCS"), c.maxDocs);
        c.maxOutputChars = integer(env.get("POPPYDB_MAX_OUTPUT_CHARS"), c.maxOutputChars);
        c.maxWaitMs = integer(env.get("POPPYDB_MAX_WAIT_MS"), c.maxWaitMs);

        // 2. command line
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--uri" -> c.applyUri(args[++i]);
                case "--hosts", "--host" -> c.hosts = split(args[++i]);
                case "--database", "--db" -> c.database = args[++i];
                case "--user" -> c.user = args[++i];
                case "--password" -> c.password = args[++i];
                case "--password-file" -> c.password = readSecret(args[++i]);
                case "--auth-db" -> c.authDb = args[++i];
                case "--replica-set" -> c.replicaSet = args[++i];
                case "--tls", "--ssl" -> c.tls = true;
                case "--allow-write" -> c.allowWrite = true;
                case "--allow-ops" -> c.allowOps = true;
                case "--max-docs" -> c.maxDocs = Integer.parseInt(args[++i]);
                case "--max-output-chars" -> c.maxOutputChars = Integer.parseInt(args[++i]);
                case "--max-wait-ms" -> c.maxWaitMs = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.err.println(USAGE);
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown option: " + a + "\n\n" + USAGE);
            }
        }
        if (c.maxDocs < 1) {
            c.maxDocs = 1;
        }
        return c;
    }

    /** Parses a (non-SRV) mongodb:// URI. */
    void applyUri(String uri) {
        if (!uri.startsWith("mongodb://")) {
            throw new IllegalArgumentException("Only mongodb:// URIs are supported (no +srv): " + uri);
        }
        String rest = uri.substring("mongodb://".length());
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String path = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            path = rest.substring(slash + 1);
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String cred = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = cred.indexOf(':');
            if (colon >= 0) {
                user = decode(cred.substring(0, colon));
                password = decode(cred.substring(colon + 1));
            } else {
                user = decode(cred);
            }
        }
        hosts = split(rest);
        if (path != null && !path.isBlank()) {
            database = decode(path);
            authDb = database; // mongo semantics: db in path is default authSource
        }
        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = kv.substring(0, eq).toLowerCase();
                String v = decode(kv.substring(eq + 1));
                switch (k) {
                    case "authsource" -> authDb = v;
                    case "replicaset" -> replicaSet = v;
                    case "tls", "ssl" -> tls = bool(v, tls);
                    default -> { /* ignored */ }
                }
            }
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static List<String> split(String s) {
        List<String> l = new ArrayList<>();
        for (String h : s.split(",")) {
            h = h.trim();
            if (h.isEmpty()) {
                continue;
            }
            l.add(h.contains(":") ? h : h + ":27017");
        }
        return l;
    }

    private static String readSecret(String file) throws Exception {
        String s = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean bool(String v, boolean def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        return switch (v.trim().toLowerCase()) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: " + v);
        };
    }

    private static int integer(String v, int def) {
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    @Override
    public String toString() {
        return "hosts=" + hosts + ", database=" + database + ", user=" + (user == null ? "-" : user)
               + ", authDb=" + authDb + ", replicaSet=" + (replicaSet == null ? "-" : replicaSet)
               + ", tls=" + tls + ", allowWrite=" + allowWrite + ", allowOps=" + allowOps
               + ", maxDocs=" + maxDocs;
    }
}
