package de.genios.mcp.poppydb;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point: {@code java -jar poppydb-mcp.jar [options]}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // stdout belongs to the MCP protocol. Anything else printing to System.out (libraries,
        // stray debug output) would corrupt the JSON-RPC stream, so it is redirected to stderr.
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);

        Config cfg;
        try {
            cfg = Config.load(args, System.getenv());
        } catch (Exception e) {
            System.err.println("[poppydb-mcp] " + e.getMessage());
            System.exit(2);
            return;
        }
        System.err.println("[poppydb-mcp] " + McpServer.VERSION + " starting: " + cfg);

        try (PoppyClient client = new PoppyClient(cfg)) {
            new McpServer(protocolOut, cfg, client).run();
        }
        System.err.println("[poppydb-mcp] stdin closed, exiting");
        System.exit(0);
    }
}
