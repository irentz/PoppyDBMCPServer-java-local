package de.genios.mcp.poppydb;

import de.caluga.morphium.Morphium;
import de.caluga.morphium.MorphiumConfig;
import de.caluga.morphium.driver.MorphiumCursor;
import de.caluga.morphium.driver.MorphiumDriver;
import de.caluga.morphium.driver.commands.GenericCommand;
import de.caluga.morphium.driver.wire.MongoConnection;
import de.caluga.morphium.driver.wire.SingleMongoConnection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Morphium's wire driver. Everything is sent as a raw command document so the
 * MCP server can use PoppyDB-specific commands (getMessagingStats, dumpStatus, replSetProgress, ...)
 * that have no typed counterpart in a regular driver.
 */
public final class PoppyClient implements AutoCloseable {

    private final Config cfg;
    private volatile Morphium morphium;

    public PoppyClient(Config cfg) {
        this.cfg = cfg;
    }

    /** Result of a cursor-producing command. */
    public record CursorResult(List<Map<String, Object>> docs, boolean truncated, String server) {
    }

    private synchronized MorphiumDriver driver() {
        if (morphium == null) {
            MorphiumConfig mc = new MorphiumConfig();
            mc.connectionSettings()
              .setDatabase(cfg.database)
              .setMaxWaitTime(cfg.maxWaitMs)
              .setConnectionTimeout(cfg.connectTimeoutMs)
              .setUseSSL(cfg.tls)
              .setMinConnections(1)
              .setMaxConnections(8)
              .setRetriesOnNetworkError(2)
              .setSleepBetweenNetworkErrorRetries(250);
            mc.clusterSettings().setHostSeed(cfg.hosts);
            if (cfg.replicaSet != null && !cfg.replicaSet.isBlank()) {
                mc.clusterSettings().setRequiredReplicaSetName(cfg.replicaSet);
            }
            if (cfg.user != null && !cfg.user.isBlank()) {
                mc.authSettings().setMongoLogin(cfg.user);
                mc.authSettings().setMongoPassword(cfg.password);
                mc.authSettings().setMongoAuthDb(cfg.authDb);
            }
            mc.driverSettings().setAppName("poppydb-mcp");
            mc.driverSettings().setReadTimeout(cfg.maxWaitMs);
            morphium = new Morphium(mc);
        }
        return morphium.getDriver();
    }

    /** Drops the current connection pool, the next call reconnects. */
    public synchronized void reset() {
        if (morphium != null) {
            try {
                morphium.close();
            } catch (Exception ignored) {
                // closing a broken pool may fail - irrelevant
            }
            morphium = null;
        }
    }

    // ---------------------------------------------------------------- single-document commands

    /**
     * Runs a command that returns a single reply document.
     *
     * @param node optional "host:port" to send the command directly to one replica set member
     */
    public Map<String, Object> command(String db, Map<String, Object> cmd, String node) throws Exception {
        return withConnection(node, con -> {
            GenericCommand gc = newCommand(con, db, cmd);
            int id = gc.executeAsync();
            Map<String, Object> r = con.readSingleAnswer(id);
            return r == null ? Map.of() : r;
        });
    }

    /**
     * Runs a command that returns a cursor (find, aggregate, listCollections, listIndexes, ...),
     * collecting at most {@code limit} documents and killing the cursor afterwards.
     */
    public CursorResult cursorCommand(String db, Map<String, Object> cmd, int limit, String node) throws Exception {
        return withConnection(node, con -> {
            GenericCommand gc = newCommand(con, db, cmd);
            int id = gc.executeAsync();
            int batch = Math.max(1, Math.min(limit + 1, 1000));
            MorphiumCursor crs = con.getAnswerFor(id, batch);
            List<Map<String, Object>> docs = new ArrayList<>();
            boolean truncated = false;
            try {
                while (crs.hasNext()) {
                    Map<String, Object> d = crs.next();
                    if (docs.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    docs.add(d);
                }
            } finally {
                try {
                    crs.close();
                } catch (Exception ignored) {
                    // cursor already exhausted
                }
            }
            return new CursorResult(docs, truncated, con.getConnectedTo() + ":" + con.getConnectedToPort());
        });
    }

    private static GenericCommand newCommand(MongoConnection con, String db, Map<String, Object> cmd) {
        GenericCommand gc = new GenericCommand(con);
        gc.setDb(db);
        gc.setCmdData(new LinkedHashMap<>(cmd));
        return gc;
    }

    @FunctionalInterface
    private interface ConCall<T> {
        T call(MongoConnection con) throws Exception;
    }

    /** "not primary"/"node is recovering": the command was rejected, not executed - safe to retry once. */
    private static final java.util.Set<Integer> RETRYABLE = java.util.Set.of(10107, 13435, 13436, 189, 11600, 11602, 91);

    private <T> T withConnection(String node, ConCall<T> call) throws Exception {
        if (node != null && !node.isBlank()) {
            return withConnectionOnce(node, call);
        }
        try {
            return withConnectionOnce(null, call);
        } catch (de.caluga.morphium.driver.MorphiumDriverException e) {
            Object code = e.getMongoCode();
            if (code instanceof Number n && RETRYABLE.contains(n.intValue())) {
                Thread.sleep(750); // let the driver's heartbeat pick up the new primary
                return withConnectionOnce(null, call);
            }
            throw e;
        }
    }

    private <T> T withConnectionOnce(String node, ConCall<T> call) throws Exception {
        MorphiumDriver drv = driver();
        if (node != null && !node.isBlank()) {
            // direct connection to one member, bypassing replica set routing
            String[] hp = node.trim().split(":");
            int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 27017;
            SingleMongoConnection con = new SingleMongoConnection();
            if (cfg.user != null && !cfg.user.isBlank()) {
                con.setCredentials(cfg.authDb, cfg.user, cfg.password);
            }
            try {
                con.connect(drv, hp[0], port);
                return call.call(con);
            } finally {
                con.close();
            }
        }
        MongoConnection con = drv.getPrimaryConnection(null);
        try {
            return call.call(con);
        } finally {
            drv.releaseConnection(con);
        }
    }

    /** Hosts currently known to the driver (seed plus discovered members). */
    public List<String> knownHosts() {
        try {
            return new ArrayList<>(driver().getHostSeed());
        } catch (Exception e) {
            return cfg.hosts;
        }
    }

    @Override
    public void close() {
        reset();
    }
}
