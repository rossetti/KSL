#!/usr/bin/env python3
"""
Minimal MCP stdio client to smoke-test the KSL Code MCP server jar end-to-end.

    python3 scripts/mcp_smoke.py build/libs/ksl-code-mcp.jar

Speaks line-delimited JSON-RPC 2.0: initialize -> initialized -> tools/list ->
a few tools/call. Paces messages and keeps stdin open (the SDK closes the session
on abrupt EOF before answering buffered requests).
"""
import json
import subprocess
import sys
import threading
import time

jar = sys.argv[1] if len(sys.argv) > 1 else "build/libs/ksl-code-mcp.jar"
proc = subprocess.Popen(
    ["java", "-jar", jar, "--stdio"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, bufsize=1,
)

# drain stderr (server logs) to a side channel so it doesn't block
def drain_err():
    for line in proc.stderr:
        sys.stderr.write("[server] " + line)
threading.Thread(target=drain_err, daemon=True).start()

def send(obj):
    proc.stdin.write(json.dumps(obj) + "\n")
    proc.stdin.flush()

def read():
    line = proc.stdout.readline()
    return json.loads(line) if line.strip() else None

def call(id, method, params=None):
    send({"jsonrpc": "2.0", "id": id, "method": method, **({"params": params} if params is not None else {})})
    time.sleep(0.3)
    return read()

# 1. handshake
init = call(1, "initialize", {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {"name": "smoke", "version": "0"},
})
print("initialize ->", json.dumps(init.get("result", {}).get("serverInfo", {})))
print("instructions present:", bool(init.get("result", {}).get("instructions")))
send({"jsonrpc": "2.0", "method": "notifications/initialized"})
time.sleep(0.3)

# 2. tools/list
tools = call(2, "tools/list")
names = [t["name"] for t in tools["result"]["tools"]]
print("tools ->", names)

# 3. a few tool calls
def tool(id, name, args):
    r = call(id, "tools/call", {"name": name, "arguments": args})
    content = r.get("result", {}).get("content", [])
    text = content[0]["text"] if content else json.dumps(r)
    print(f"\n===== {name}({args}) =====")
    print(text[:1400])

tool(3, "get_server_info", {})
tool(4, "search_code", {"query": "seize and release a resource", "maxResults": 3})
tool(5, "get_class", {"fqn": "ksl.modeling.entity.Resource"})
tool(6, "find_subclasses", {"fqn": "ModelElement"})
tool(7, "get_example", {"fqn": "ProcessModel"})

time.sleep(0.3)
proc.stdin.close()
proc.terminate()
print("\nOK: server answered all requests.")
