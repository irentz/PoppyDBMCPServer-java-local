# poppydb-mcp – lokaler MCP-Server für PoppyDB

Ein MCP-Server (stdio) in Java, der Claude (Desktop-App, Claude Code) direkten Zugriff auf eine
[PoppyDB](https://github.com/sboesebeck/morphium/blob/master/docs/poppydb.md) gibt – über den
Morphium-Wire-Treiber, also mit allen PoppyDB-spezifischen Befehlen (`getMessagingStats`,
`dumpStatus`, `poppyRestoreStatus`, `getLog`, …), die der offizielle `mongodb-mcp-server` nicht kennt.

* Einzige Laufzeitabhängigkeit: **Morphium 6.3.12** (kein MCP-SDK – das Protokoll ist direkt implementiert)
* Java 21, ein Fat-Jar (~12 MB)
* Standardmäßig **nur lesend**; Schreiben und Betriebsaktionen nur per Schalter

## Tools

| Gruppe | Tools | Freischaltung |
|---|---|---|
| Daten lesen | `list_databases`, `list_collections`, `find`, `count`, `aggregate`, `distinct`, `list_indexes`, `collection_stats`, `db_stats` | immer |
| Admin / Status | `server_info`, `cluster_overview`, `replset_status`, `restore_status`, `get_log`, `messaging_stats`, `dump_status`, `current_op`, `server_options`, `run_command` | immer |
| Schreiben | `insert_documents`, `update_documents`, `delete_documents`, `create_index`, `drop_index`, `drop_collection` | `--allow-write` |
| Betrieb | `replset_step_down`, `replset_freeze`, `dump_now`, `kill_op`, `set_log_level`, `shutdown_node` | `--allow-ops` |

Hinweise:

* Alle Admin-Tools haben einen optionalen Parameter `node` (`host:port`), um **ein einzelnes
  Replica-Set-Mitglied direkt** zu fragen (sonst: aktueller Primary). `cluster_overview` fragt alle
  bekannten Knoten nacheinander und zeigt, wen jeder für den Primary hält, Term, State usw. –
  der beste Einstieg bei Replikations-/Wahlproblemen.
* `run_command` führt beliebige Kommando-Dokumente aus, aber klassifiziert: Lese-Kommandos immer,
  Schreib-Kommandos nur mit `--allow-write`, Betriebs- und unbekannte Kommandos nur mit
  `--allow-ops`; replikationsinterne Kommandos (`appendEntries`, `requestVote`, `replSetProgress`, …)
  und Benutzerverwaltung nie.
* `aggregate` mit `$out`/`$merge` braucht `--allow-write`.
* Sicherheitsnetze: Multi-Update mit leerem Filter wird abgelehnt, Löschen *aller* Dokumente
  braucht `confirm: true`, `drop_collection`/`shutdown_node` verlangen die Wiederholung des
  Namens/der Adresse in `confirm`. Teilweise fehlgeschlagene Writes (`writeErrors`) werden als
  Fehler gemeldet.
* Filter/Dokumente akzeptieren MongoDB Extended JSON (`{"$oid": "…"}`, `{"$date": "2026-01-01T00:00:00Z"}`,
  `{"$numberLong": "…"}`, `{"$regularExpression": …}`), Ergebnisse werden ebenso ausgegeben.
* Ergebnismengen sind begrenzt (`--max-docs`, Standard 100; `find` liefert ohne `limit` 20) und
  die Ausgabe wird nach `--max-output-chars` abgeschnitten – mit Hinweis `truncated`.
* Bei „not primary“/„node is recovering“ (z. B. direkt nach einem Step-Down) wird einmal
  automatisch wiederholt; die Verbindung wird lazy aufgebaut, der Server startet also auch,
  wenn PoppyDB (noch) nicht läuft.

## Bauen

```bash
cd poppydb-mcp
mvn -o clean package          # -o: offline, alle Abhängigkeiten liegen schon in ~/.m2
# → target/poppydb-mcp.jar
```

Ohne `-o` geht es natürlich auch (dann über euer Nexus/Maven Central). Ein bereits gebautes und
getestetes Jar liegt als `dist/poppydb-mcp.jar` bei.

Kurztest von Hand (eine Zeile JSON-RPC rein, eine Zeile raus):

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"server_info","arguments":{}}}' \
  | java -jar target/poppydb-mcp.jar --hosts localhost:17017
```

## Konfiguration

Jede Option geht als Argument oder Umgebungsvariable (Argument gewinnt):

| Argument | Umgebungsvariable | Standard |
|---|---|---|
| `--uri mongodb://[user:pw@]h1:p1,h2:p2/[db]?authSource=admin&replicaSet=rs0&tls=true` | `POPPYDB_URI` | – |
| `--hosts h1:p1,h2:p2` | `POPPYDB_HOSTS` | `localhost:17017` |
| `--database <db>` | `POPPYDB_DATABASE` | `test` |
| `--user <name>` | `POPPYDB_USER` | – |
| `--password <pw>` (besser Env/Datei) | `POPPYDB_PASSWORD` | – |
| `--password-file <pfad>` | `POPPYDB_PASSWORD_FILE` | – |
| `--auth-db <db>` | `POPPYDB_AUTH_DB` | `admin` |
| `--replica-set <name>` | `POPPYDB_REPLICA_SET` | – |
| `--tls` | `POPPYDB_TLS=true` | aus |
| `--allow-write` | `POPPYDB_ALLOW_WRITE=true` | aus |
| `--allow-ops` | `POPPYDB_ALLOW_OPS=true` | aus |
| `--max-docs <n>` | `POPPYDB_MAX_DOCS` | 100 |
| `--max-output-chars <n>` | `POPPYDB_MAX_OUTPUT_CHARS` | 100000 |
| `--max-wait-ms <n>` | `POPPYDB_MAX_WAIT_MS` | 15000 |

Log-Level des Treibers: `POPPYDB_MCP_LOG_LEVEL=INFO` (Standard `WARN`). Alle Logs gehen nach
**stderr** – stdout gehört ausschließlich dem MCP-Protokoll.

**SOCKS-Proxy** (z. B. euer `localhost:5555` für acc/prod): JVM-Optionen
`-DsocksProxyHost=localhost -DsocksProxyPort=5555` vor `-jar` setzen – Morphium öffnet normale
`java.net.Socket`s, die den Proxy dann automatisch nutzen.

## Einbinden in die Claude-Desktop-App

`~/Library/Application Support/Claude/claude_desktop_config.json` → im Block `mcpServers`
ergänzen (Beispiel siehe `claude_desktop_config.example.json`). Wichtig:

* **Absoluten Pfad zu Java 21** angeben – die App startet Prozesse ohne eure Shell-`PATH`.
  Pfad ermitteln: `/usr/libexec/java_home -v 21` → `<pfad>/bin/java`.
* Absoluten Pfad zum Jar angeben.
* Pro Umgebung einen eigenen Eintrag (z. B. `poppydb-local` mit Schreibrechten, `poppydb-acc` nur lesend).
* Danach die Claude-App komplett beenden und neu starten.

```json
{
  "mcpServers": {
    "poppydb-local": {
      "command": "/Library/Java/JavaVirtualMachines/<jdk-21>/Contents/Home/bin/java",
      "args": ["-jar", "/Users/rentzi/Genios/Claude/poppydb-mcp/dist/poppydb-mcp.jar",
               "--hosts", "localhost:17017", "--allow-write"]
    }
  }
}
```

Für Claude Code:

```bash
claude mcp add poppydb-local -- /pfad/zu/java -jar /pfad/zu/poppydb-mcp.jar --hosts localhost:17017
```

## Tests

`test/` enthält zwei Python-Skripte, die den Server wie ein MCP-Client über stdio fahren:

```bash
# PoppyDB starten, z. B.: java -jar poppydb-6.3.12-cli.jar --no-config -p 17017
python3 test/mcp_smoke_test.py java -jar target/poppydb-mcp.jar --hosts localhost:17017 --allow-write --allow-ops
python3 test/mcp_smoke_test.py java -jar target/poppydb-mcp.jar --hosts localhost:17017      # Read-only-Gating

# 3-Knoten-Replica-Set (17020-17022, --rs-name rs0): Cluster-Übersicht, Node-Targeting, Step-Down + Failover
python3 test/mcp_replset_test.py java -jar target/poppydb-mcp.jar \
  --uri "mongodb://localhost:17020,localhost:17021,localhost:17022/test?replicaSet=rs0" --allow-write --allow-ops
```

Getestet mit PoppyDB 6.3.12 (Einzelknoten, 3er-Replica-Set, `--auth` mit SCRAM, Persistenz mit
`--dump-dir`).

## Aufbau

```
src/main/java/de/genios/mcp/poppydb/
  Main.java         Einstieg, stdout-Schutz
  McpServer.java    JSON-RPC/MCP über stdio (initialize, tools/list, tools/call, ping, Batches)
  PoppyTools.java   alle Tools + Kommando-Klassifizierung
  PoppyClient.java  Morphium-Wrapper: Kommandos, Cursor, Direktverbindung zu einem Knoten
  Tool.java         Tool-Metadaten, JSON-Schema, Argument-Zugriff
  Json.java         JSON-Parser/-Writer mit Extended-JSON-Unterstützung
  Config.java       Argumente, Umgebungsvariablen, mongodb://-URI
```
