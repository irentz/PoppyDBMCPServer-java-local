#!/usr/bin/env python3
"""Replica set test: cluster_overview, per-node admin tools, write to primary, step down + failover.

usage: mcp_replset_test.py <java command ...>   (server must be started with --allow-write --allow-ops)
"""
import json
import subprocess
import sys
import threading
import time

p = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                     text=True, bufsize=1)
err = []
threading.Thread(target=lambda: [err.append(l) for l in p.stderr], daemon=True).start()
_id = 0
fails = 0


def rpc(method, params=None):
    global _id
    _id += 1
    p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}) + "\n")
    p.stdin.flush()
    while True:
        r = json.loads(p.stdout.readline())
        if r.get("id") == _id:
            return r


def call(name, args=None, expect_error=False):
    global fails
    res = rpc("tools/call", {"name": name, "arguments": args or {}})["result"]
    text = res["content"][0]["text"]
    ok = res["isError"] == expect_error
    fails += 0 if ok else 1
    print(f"[{'OK  ' if ok else 'FAIL'}] {name} {json.dumps(args or {})}")
    print("       " + (text if len(text) < 1500 else text[:1500] + " ...").replace("\n", "\n       "))
    return json.loads(text) if not res["isError"] else text


rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "rs", "version": "1"}})

ov = call("cluster_overview")
primary = [n["node"] for n in ov["nodes"] if n.get("isWritablePrimary")]
print("primary:", primary)
assert len(primary) == 1, ov
primary = primary[0]
secondary = [n["node"] for n in ov["nodes"] if not n.get("isWritablePrimary")][0]

call("replset_status", {"node": secondary})
call("get_log", {"node": secondary, "contains": "leader", "limit": 3})
call("restore_status", {"node": secondary})
call("insert_documents", {"collection": "rs_test", "documents": [{"k": i} for i in range(5)]})
time.sleep(1)
call("count", {"collection": "rs_test"})
call("run_command", {"database": "test", "node": secondary, "command": {"count": "rs_test"}})
call("replset_freeze", {"node": secondary, "seconds": 0})
call("replset_step_down", {"node": primary, "stepDownSecs": 30, "force": True})

new_primary = None
for _ in range(30):
    time.sleep(1)
    ov = rpc("tools/call", {"name": "cluster_overview", "arguments": {}})["result"]
    nodes = json.loads(ov["content"][0]["text"])["nodes"]
    prim = [n["node"] for n in nodes if n.get("isWritablePrimary")]
    if len(prim) == 1 and prim[0] != primary:
        new_primary = prim[0]
        break
print("new primary after step down:", new_primary)
if not new_primary:
    fails += 1
# the pooled driver must follow the new primary for writes
for attempt in range(10):
    r = rpc("tools/call", {"name": "insert_documents",
                           "arguments": {"collection": "rs_test", "documents": [{"after": "stepdown"}]}})["result"]
    if not r["isError"]:
        break
    time.sleep(1)
print("[%s] write after failover: %s" % ("OK  " if not r["isError"] else "FAIL", r["content"][0]["text"][:300]))
fails += 1 if r["isError"] else 0
call("cluster_overview")

p.stdin.close()
p.wait(timeout=20)
print("failures:", fails)
print("stderr tail:\n  " + "  ".join(err[-6:]))
sys.exit(1 if fails else 0)
