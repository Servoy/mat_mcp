# mat-mcp — Eclipse MAT heap-dump analysis over MCP

An Eclipse feature, built **on top of Eclipse Memory Analyzer (MAT)**, that exposes MAT's
heap-dump analysis to AI agents through the **Model Context Protocol (MCP)**.

It installs into a stock MAT, auto-starts an MCP server over HTTP, and lets an MCP client
query the heap dump you have open in MAT — with results returned as structured JSON.

## Why

Analyzing a large heap dump by repeatedly launching MAT headless (`eclipsec.exe` with a
generated report XML per question) is slow and awkward:

- every question re-launches a full Equinox/OSGi runtime and re-reads the dump indexes;
- OQL has to be smuggled through report-XML `<command>` tags, which is fragile (schema and
  parser errors) and returns HTML that then has to be scraped;
- the report/OQL route cannot read **thread-stack frame locals**, which is exactly what you
  need to find things like the actual array/argument a method held on a frame in an OOM stack.

This project instead keeps the MAT runtime alive with the snapshot parsed **once** (the dump
you open in MAT), and answers each question against that open `ISnapshot`. OQL, dominator
tree, path-to-GC-roots, object inspection and thread-stack locals all become cheap follow-up
calls, returned as JSON.

## Architecture

```
MCP client (opencode / OpenChamber / ...)
        │  HTTP, MCP Streamable  →  http://127.0.0.1:8923/mcp
        ▼
MAT process (Eclipse 4 workbench)
        ├─ com.servoy.mat.mcp bundle
        │     ├─ StartupMcpServer (org.eclipse.ui.startup) ── auto-starts the server on launch
        │     ├─ McpHttpServer ── embedded Jetty (EE10) hosting the MCP SDK servlet transport
        │     ├─ MatMcpTools / InspectionTools / OqlRunner ── the 14 tools, JSON out
        │     └─ ActiveSnapshot ── reads the ISnapshot of the active MAT heap-editor tab
        └─ MAT API: org.eclipse.mat.snapshot.* (ISnapshot, IObject, IThreadStack, ...)
```

Key design points:

- **Runs inside MAT, over HTTP (not stdio).** The server lives for the whole MAT process so
  the parsed snapshot stays resident; a client connects by URL instead of spawning a
  short-lived process per request. Host/port: `-Dmat.mcp.host` / `-Dmat.mcp.port`
  (default `127.0.0.1:8923`), endpoint `/mcp`.
- **Follows the active heap-editor.** The tools operate on the dump you have open in MAT;
  `open_snapshot` opens one as a MAT editor tab so it becomes the active snapshot.
- **Public MAT API where possible.** Tools build on exported packages
  (`org.eclipse.mat.snapshot`, `.snapshot.model`, `.snapshot.query`, `org.eclipse.mat.query`).
  Reading the active editor's snapshot needs MAT's non-exported `HeapEditor` /
  `ISnapshotEditorInput`, which is done reflectively (see AGENTS.md); a proper fix is a MAT
  pull request to make that editor API public.

## MCP tools

| Tool | What it does |
|------|--------------|
| `open_snapshot(hprofPath)` | Open a .hprof as a MAT editor tab (becomes the active snapshot); returns the overview. Parses asynchronously, so the first call may return `{"status":"opening"}`. |
| `snapshot_overview()` | Object/class/GC-root counts, used heap, identifier size, JVM info of the active snapshot. |
| `oql(query, maxRows?)` | Run MAT OQL; rows / object ids as JSON, capped by `maxRows`. |
| `histogram(classNamePattern?, limit?)` | Top classes by shallow size and object count, optional class-name regex. |
| `read_object(objectId, maxReferences?)` | One object: identity, sizes, GC-root flags, instance fields (or a class's static fields) with resolved values, and named outbound references. |
| `array_elements(objectId, from?, limit?)` | Elements of an array object: primitive values inline, object descriptors for a reference array; windowed by from/limit. |
| `outbound_references(objectId, limit?)` | Objects this object points to. |
| `inbound_references(objectId, limit?)` | Objects that point to this object (referrers). |
| `path_to_gc_roots(objectId)` | Shortest reference path to a GC root — why the object is kept alive. |
| `retained_set(objectIds)` | Heap that would be freed if the given objects were collected. |
| `top_dominator_classes(limit?)` | Classes retaining the most heap (dominator tree grouped by class). |
| `class_details(name, regex?, limit?)` | Find classes by name/regex: counts, per-instance size, superclass, array flag. |
| `gc_roots(limit?)` | GC roots with class and root type. |
| `thread_stacks(maxLocalsPerFrame?)` | Every thread with its stack frames **and the local objects per frame** — the capability OQL cannot provide. |

Every object is reported with both its MAT `objectId` (stable for the snapshot's life, accepted
by the other tools) and its hex `address`, so calls can be chained
(`oql` → `objectId` → `path_to_gc_roots`).

## Install

1. Build the update site (see below) or use `com.servoy.mat.mcp.repository/target/*.zip`.
2. In MAT: **Help → Install New Software → Add → Archive**, select the zip, install the
   "MAT MCP Server" feature, restart MAT.
3. On restart the server auto-starts; the toolbar shows a green **M** icon when it is
   listening (grey when stopped). The icon is also a start/stop toggle.

## Build

- **Target platform:** the local MAT install (`C:/javatools/mat`) plus the MCP Java SDK and the
  Jetty EE10 servlet layer, declared in `releng/mat-mcp.target`.
- **Build:** Eclipse PDE / Tycho. `mvn clean verify` builds the plugin, feature and p2
  repository.
- Requires Java 21+ (matching the MAT install's JVM).

## Modules

- `com.servoy.mat.mcp` — the plugin (MCP server, tools, UI).
- `com.servoy.mat.mcp.feature` — installable feature bundling the plugin + the runtime bundles
  MAT lacks (MCP SDK, reactor, Jetty EE10, ...).
- `com.servoy.mat.mcp.repository` — p2 update site (`eclipse-repository`).
- `releng/mat-mcp.target` — the target-platform definition.

See **AGENTS.md** for the implementation notes and the hard-won gotchas (OSGi access layers,
E4 vs 3.x UI, the JSON-mapper/validator service-loader trap, auto-start).
