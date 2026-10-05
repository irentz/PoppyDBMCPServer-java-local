#!/usr/bin/env python3
"""Smoke test: drives poppydb-mcp over stdio like an MCP client (Claude Desktop) would.

usage: mcp_smoke_test.py <java command ...>
   e.g. mcp_smoke_test.py java -jar target/poppydb-mcp.jar --hosts localhost:17017 --allow-write --allow-ops
"""
import json
import subprocess
import sys
import threading

cmd = sys.argv[1:]
p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
stderr_lines = []
threading.Thread(target=lambda: [stderr_lines.append(l) for l in p.stderr], daemon=True).start()

_id = 0
failures = 0


def rpc(method, params=None, notify=False):
    global _id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        _id += 1
        msg["id"] = _id
    p.stdin.write(json.dumps(msg) + "\n")
    p.stdin.flush()
    if notify:
        return None
    while True:
        line = p.stdout.readline()
        if not line:
            raise SystemExit("server closed stdout. stderr:\n" + "".join(stderr_lines))
        resp = json.loads(line)  # every stdout line MUST be valid JSON
        if resp.get("id") == msg["id"]:
            return resp


def call(name, args=None, expect_error=False, show=True):
    global failures
    r = rpc("tools/call", {"name": name, "arguments": args or {}})
    if "error" in r:
        ok = expect_error
        text = "RPC ERROR " + r["error"]["message"]
    else:
        res = r["result"]
        text = res["content"][0]["text"]
        ok = res["isError"] == expect_error
    status = "OK  " if ok else "FAIL"
    if not ok:
        failures += 1
    preview = text if len(text) < 600 else text[:600] + " ..."
    print(f"[{status}] {name} {json.dumps(args or {})[:120]}")
    if show or not ok:
        print("       " + preview.replace("\n", "\n       "))
    return text


init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                          "clientInfo": {"name": "smoke", "version": "1"}})
print("initialize ->", init["result"]["protocolVersion"], init["result"]["serverInfo"])
rpc("notifications/initialized", notify=True)
assert rpc("ping")["result"] == {}
tools = rpc("tools/list")["result"]["tools"]
print("tools:", ", ".join(t["name"] for t in tools))
names = {t["name"] for t in tools}

EXPECT_RESTORE_ERR = not any("--replica" in a or "," in a for a in cmd)
if "insert_documents" in names:
    call("drop_collection", {"collection": "mcp_test", "confirm": "mcp_test"}, show=False)
    call("drop_collection", {"collection": "mcp_bulk", "confirm": "mcp_bulk"}, show=False)
    call("insert_documents", {"collection": "mcp_test", "documents": [
        {"_id": {"$oid": "65f1a2b3c4d5e6f708192a3b"}, "name": "alpha", "n": 1, "tags": ["a", "b"],
         "ts": {"$date": "2026-10-01T12:00:00Z"}, "big": {"$numberLong": "9007199254740993"}},
        {"name": "beta", "n": 2, "tags": ["b"]},
        {"name": "gamma", "n": 3, "nested": {"x": 1.5}}]})
    call("update_documents", {"collection": "mcp_test", "filter": {"name": "beta"}, "update": {"$inc": {"n": 10}}})
    call("update_documents", {"collection": "mcp_test", "filter": {}, "update": {"$set": {"x": 1}}, "multi": True},
         expect_error=True)
    call("create_index", {"collection": "mcp_test", "keys": {"name": 1}, "unique": True})
    call("insert_documents", {"collection": "mcp_bulk", "documents": [{"i": i} for i in range(250)]}, show=False)

call("list_databases")
call("list_collections", {})
call("find", {"collection": "mcp_test", "filter": {"n": {"$gte": 2}}, "sort": {"n": -1}})
call("find", {"collection": "mcp_test", "filter": {"_id": {"$oid": "65f1a2b3c4d5e6f708192a3b"}}})
call("find", {"collection": "mcp_test", "filter": {"ts": {"$gt": {"$date": "2026-01-01T00:00:00Z"}}},
              "projection": {"name": 1, "ts": 1, "big": 1}})
call("find", {"collection": "mcp_test", "filter": "{\"name\": \"gamma\"}"})  # stringified filter
t = call("find", {"collection": "mcp_bulk", "limit": 5}, show=False)
RW = "insert_documents" in names
assert not RW or ('"returned": 5' in t and '"truncated": true' in t), t
t = call("find", {"collection": "mcp_bulk", "limit": 100000}, show=False)
assert not RW or '"returned": 100' in t, t[:200]
call("count", {"collection": "mcp_bulk", "filter": {"i": {"$lt": 100}}})
call("aggregate", {"collection": "mcp_bulk", "pipeline": [{"$match": {"i": {"$gte": 200}}},
                                                          {"$group": {"_id": None, "sum": {"$sum": "$i"}, "c": {"$sum": 1}}}]})
t = call("aggregate", {"collection": "mcp_bulk", "pipeline": [{"$sort": {"i": 1}}], "limit": 3}, show=False)
assert not RW or '"returned": 3' in t, t
call("distinct", {"collection": "mcp_test", "key": "tags"})
call("list_indexes", {"collection": "mcp_test"})
call("collection_stats", {"collection": "mcp_test"})
call("db_stats", {})

call("server_info", {})
call("cluster_overview", {})
call("replset_status", {"includeConfig": True})
call("restore_status", {}, expect_error=EXPECT_RESTORE_ERR)
call("get_log", {"limit": 5})
call("get_log", {"log": "startupWarnings"})
call("messaging_stats", {})
call("dump_status", {})
call("current_op", {})
call("server_options", {})
call("run_command", {"command": {"listCommands": 1}}, show=False)
call("run_command", {"command": {"ping": 1}})
call("run_command", {"command": {"appendEntries": 1}}, expect_error=True)
call("run_command", {"command": {"requestVote": 1}}, expect_error=True)
call("run_command", {"database": "test", "command": {"find": "mcp_test", "filter": {"n": 1}}})

if "insert_documents" not in names:
    call("run_command", {"database": "test", "command": {"insert": "x", "documents": [{"a": 1}]}}, expect_error=True)
    call("run_command", {"command": {"shutdown": 1}}, expect_error=True)
    call("aggregate", {"collection": "mcp_test", "pipeline": [{"$out": "copy"}]}, expect_error=True)
    r = rpc("tools/call", {"name": "insert_documents", "arguments": {}})
    print("[OK  ] insert_documents hidden ->", r.get("error", {}).get("message"))
else:
    call("delete_documents", {"collection": "mcp_test", "filter": {}, "multi": True}, expect_error=True)
    call("delete_documents", {"collection": "mcp_test", "filter": {"name": "gamma"}})
    call("insert_documents", {"collection": "mcp_test", "documents": [{"name": "alpha"}]}, expect_error=True)  # unique
    call("drop_collection", {"collection": "mcp_bulk", "confirm": "wrong"}, expect_error=True)
    call("drop_collection", {"collection": "mcp_bulk", "confirm": "mcp_bulk"})

if "dump_now" in names:
    call("dump_now", {})
    call("dump_status", {})
    call("set_log_level", {"level": 1})
    call("server_options", {}, show=False)
    call("set_log_level", {"level": 0})
    call("kill_op", {"op": 999999}, expect_error=True)

# error handling
call("find", {"collection": "mcp_test", "filter": {"$bogus": 1}}, expect_error=True)
call("find", {}, expect_error=True)
bad = rpc("tools/call", {"name": "nope"})
assert "error" in bad
assert "error" in rpc("does/not/exist")

p.stdin.close()
p.wait(timeout=20)
print("\nexit code", p.returncode, "| failures:", failures)
print("stderr tail:\n  " + "  ".join(stderr_lines[-8:]))
sys.exit(1 if failures else 0)
