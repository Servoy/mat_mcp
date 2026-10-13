# AGENTS.md — mat-mcp implementation notes & gotchas

Context for anyone (human or AI) working on this repo. It records *what the project is*, *how it
is put together*, and especially the **non-obvious traps** that cost real time — so they are not
rediscovered the hard way.

## What this is

An Eclipse plugin/feature that runs **inside Eclipse Memory Analyzer (MAT)** and exposes MAT's
heap-dump analysis over the **Model Context Protocol (MCP)**, so an AI agent can inspect a heap
dump with structured JSON instead of MAT's HTML reports. It serves over **HTTP** (MCP Streamable
HTTP) at `http://127.0.0.1:8923/mcp`, auto-starts when MAT launches, and operates on the heap
dump open in MAT's active editor.

Primary motivating case: SVY-21574, an OutOfMemory where a `ServoyJSONArray` grew a backing
`Object[]` of ~2 GB. The tools reproduce the whole diagnosis live: `oql` finds the array,
`path_to_gc_roots` shows the `Worker-2: Loading servers/tables` thread holding it, and
`thread_stacks` shows the 2 GB `Object[]` as a local on the `Arrays.copyOf` frame.

## Module layout

- `com.servoy.mat.mcp` — the plugin. All server + tool + UI code is in
  `src/com/servoy/mat/mcp/internal`.
- `com.servoy.mat.mcp.feature` — installable feature; also lists the runtime bundles MAT does
  **not** already ship (MCP SDK, reactor-core, reactive-streams, jackson-annotations,
  jetty-ee10-servlet). The core Jetty/slf4j bundles already in MAT are not duplicated.
- `com.servoy.mat.mcp.repository` — `eclipse-repository` p2 update site; `includeAllDependencies`
  so it installs into a stock MAT.
- `releng/mat-mcp.target` — target platform: Eclipse 2026-03 + the local MAT directory
  (`C:/javatools/mat`) + Maven-resolved MCP SDK and jetty-ee10-servlet.

## Build / test loop

- `mvn clean verify` from the repo root builds all four modules; the update site lands in
  `com.servoy.mat.mcp.repository/target/*.zip`.
- There is no automated runtime test; verification is: install the feature into MAT, restart,
  then drive the HTTP endpoint. A minimal MCP handshake over HTTP:
  `initialize` → `notifications/initialized` → `tools/list` / `tools/call`, sending
  `Accept: application/json, text/event-stream` and carrying the `Mcp-Session-Id` response header
  back on subsequent requests.
- Inspecting MAT's own API/bytecode with `javap -classpath <mat>/plugins/...jar` is the fastest
  way to confirm a method signature before coding against it.

## The gotchas (each one cost time — read before changing these areas)

### 1. The bundle must be *started*, not just resolved
Nothing in MAT references this bundle, so a lazy `BundleActivator` never runs and a Declarative
Services `immediate` component is also unreliable (observed: often never activated). The reliable
trigger is the **`org.eclipse.ui.startup`** extension (`StartupMcpServer`): the workbench always
invokes registered startups, which both runs our code and loads the bundle. Do **not** go back to
a DS component or `p2.inf markStarted` for auto-start.

### 2. JSON mapper/validator: do not rely on the MCP service-loader in OSGi
`McpServer.build()` and the transport normally resolve the JSON mapper and JSON-schema validator
via a `ServiceLoader`/SCR-registered supplier. In this OSGi runtime that supplier is not
registered when we need it → `No McpJsonMapperSupplier available` /
`No JsonSchemaValidatorSupplier available`. **Construct them explicitly**:
`new JacksonMcpJsonMapper(new ObjectMapper())` and `new DefaultJsonSchemaValidator(new ObjectMapper())`,
and pass both into the transport builder and `McpServer.sync(...)`.

### 3. Serve over HTTP, bind off the UI thread, open editors async
- stdio would mean a process per session; we need the snapshot resident, so it is HTTP.
- MAT ships Jetty 12.1.6 but only the **EE8 (javax)** servlet layer; the MCP servlet transport
  needs **jakarta.servlet**, so the feature pulls `jetty-ee10-servlet` 12.1.6 (reusing MAT's core
  Jetty bundles).
- `open_snapshot` opens the MAT editor with `Display.asyncExec` and does **not** wait for MAT's
  parse Job. Waiting inside `syncExec` deadlocks against the UI thread / modal progress. It returns
  `{"status":"opening"}`; the snapshot shows up via the active editor once parsing finishes.

### 4. Reading the active editor's snapshot = reflection on a non-exported package
MAT's `HeapEditor` and `ISnapshotEditorInput` live in **`org.eclipse.mat.ui.snapshot.editor`,
which MAT does not export at all** (not even x-friends). So:
- no OSGi import (normal / dynamic / x-friends) can load those types from our bundle;
- `ActiveSnapshot` opens the dump via the **public** `IDE.openEditor(page, uri, HEAP_EDITOR_ID, …)`
  and reads the snapshot off the active `IEditorPart` **reflectively**;
- reflection must resolve `getSnapshotInput()`/`hasSnapshot()`/`getSnapshot()` through a **public
  declaring supertype/interface**, otherwise it throws `IllegalAccessException` (the concrete
  classes are bundle-private, not just the package).
A proper fix is a MAT PR to make this editor API public; until then, keep the reflection isolated
in `ActiveSnapshot`.

### 5. Toolbar status icon: MAT is an E4 workbench — `IElementUpdater` does NOT fire
The classic 3.x pattern (`IElementUpdater.updateElement` + `ICommandService.refreshElements`) does
**not** drive a toolbar item in the E4 workbench MAT uses (measured: `updateElement` is never
invoked, even with `defaultHandler` and a toggle state). The working approach is to update the
**live E4 model item** directly: `StatusToolbar` finds the `MToolItem`
(`elementId = com.servoy.mat.mcp.toolitem`) via `EModelService.findElements(MApplication, …)` and
calls `setIconURI()` / `setTooltip()`; the renderer repaints it. Call `StatusToolbar.refresh()`
after every start/stop and once the auto-start bind completes. Do not reintroduce
`IElementUpdater` here.

### 6. Logging goes to the Eclipse log, not SLF4J
SLF4J in MAT is routed to `slf4j.simple` → stderr, invisible in a normally launched MAT. Use the
`Log` helper (Eclipse `ILog` via `Platform.getLog(bundle)`); entries then show in
`.metadata/.log` and the **Error Log** view, which is what makes failures diagnosable.

## Where things live

| Concern | Class |
|---|---|
| Auto-start on workbench launch | `ui.StartupMcpServer` (`org.eclipse.ui.startup`) |
| Embedded Jetty + MCP server + mapper/validator | `McpHttpServer` |
| One server + session, start/stop | `McpServerController` |
| Tool definitions / argument parsing / wiring | `MatMcpTools` |
| Heap-analysis tool logic (JSON) | `InspectionTools`, `OqlRunner` |
| Object/overview JSON helpers | `ObjectJson`, `SnapshotJson`, `Json` |
| Active-editor snapshot (reflection) | `ActiveSnapshot` |
| Toolbar toggle + live state icon | `ui.ToggleMcpServerHandler`, `ui.StatusToolbar` |
| Eclipse-log logging | `Log` |

## Conventions

- Java 21, tabs for indentation, EPL-2.0 header on each file.
- Keep tool output compact JSON built with the `Json` helper (no extra JSON lib on the plugin
  classpath beyond what the MCP SDK already brings).
- When adding a tool: implement the JSON in `InspectionTools`, wire it in `MatMcpTools` via the
  `withSnapshot(...)` wrapper, and give it a clear `description`/`inputSchema`.
