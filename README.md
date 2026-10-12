# mat-mcp — Eclipse MAT heap-dump analysis over MCP

An Eclipse feature, built **on top of Eclipse Memory Analyzer (MAT)**, that exposes
MAT's heap-dump analysis to AI agents through the **Model Context Protocol (MCP)**.

## Why

Analyzing a large heap dump by repeatedly launching MAT headless (`eclipsec.exe` with a
generated report XML per question) is slow and awkward:

- every question re-launches a full Equinox/OSGi runtime and re-reads the dump indexes;
- OQL has to be smuggled through report-XML `<command>` tags, which is fragile (schema and
  parser errors) and returns HTML that then has to be scraped;
- the report/OQL route cannot read **thread-stack frame locals**, which is exactly what you
  need to find things like the actual `index` argument on a frame in an OOM stack.

This project instead keeps **one long-lived MAT runtime** with the snapshot parsed **once**
in memory, and answers each question against that open `ISnapshot`. The dear parse happens
a single time; OQL / dominator-tree / path-to-GC-roots / object inspection then all become
cheap follow-up calls, and results are returned as structured **JSON** instead of HTML.

## Architecture

```
opencode / OpenChamber  ──MCP (stdio)──▶  mat-mcp OSGi plugin (one Equinox process)
                                              │
                                              ├─ MAT API: org.eclipse.mat.snapshot.*
                                              │     SnapshotFactory.openSnapshot(...)  → ISnapshot (kept open)
                                              │     IOQLQuery / SnapshotFactory.createQuery
                                              │     IThreadStack.getLocalVariables()   ← frame locals (not available via OQL)
                                              │
                                              └─ MCP server (io.modelcontextprotocol.sdk:mcp-core)
                                                    tools map 1:1 onto ISnapshot operations, JSON out
```

- **One process.** The plugin runs as a headless Equinox application that both opens the
  MAT snapshot and hosts the MCP server, so the parsed snapshot and its indexes stay in
  memory for the whole session.
- **Public MAT API only, where possible.** We build on the exported packages
  (`org.eclipse.mat.snapshot`, `.snapshot.model`, `.snapshot.query`, `org.eclipse.mat.query`,
  `.query.results`, `org.eclipse.mat.parser.model`). Where a needed capability is only
  reachable through an `x-friends` / internal package, we isolate that behind our own
  interface and — if it turns out to be genuinely required — raise it upstream as a MAT
  pull request to get it promoted to public API, rather than depending on internals forever.

## Planned MCP tools

| Tool | MAT backing | Notes |
|------|-------------|-------|
| `open_snapshot(hprofPath)` | `SnapshotFactory.openSnapshot` | parse/load once, return a session handle + overview |
| `snapshot_overview()` | `ISnapshot` counts | #objects, #classes, used heap, identifier size |
| `oql(query)` | `SnapshotFactory.createQuery` / `IOQLQuery` | returns rows as JSON, not HTML |
| `histogram(limit)` | class histogram | top-N classes by count / shallow / retained |
| `dominator_tree(objectId?, limit)` | dominator tree | biggest retainers |
| `path_to_gc_roots(objectId)` | `ISnapshot.getPathsFromGCRoots` / merged paths | why an object is kept alive |
| `inbound_references(objectId)` / `outbound_references(objectId)` | `getInboundRefererentIds` / outbound | reference navigation |
| `read_object(objectId)` | `IObject` fields | field names + resolved values |
| `thread_stacks()` | `IThreadStack` / `getStackFrames` / `getLocalVariables` | **frame locals** — the gap OQL cannot fill |
| `leak_suspects()` | MAT leak report | optional convenience, first-pass triage |

## Build

- **Target platform:** the local MAT install (`c:\javatools\mat`) plus the MCP Java SDK
  jars, declared in `releng/mat-mcp.target`.
- **Build system:** Eclipse PDE / Tycho (plugin + feature). `mvn clean verify` once the
  Tycho `pom.xml` files are in place.
- Requires Java 21+ (matching the MAT install's JVM).

## Status

Skeleton stage: repo + PDE plugin `com.servoy.mat.mcp` + feature + target definition, with a
first `open_snapshot` tool that opens an `ISnapshot` and returns the heap overview. Everything
else above is scaffolding to be filled in iteratively.
