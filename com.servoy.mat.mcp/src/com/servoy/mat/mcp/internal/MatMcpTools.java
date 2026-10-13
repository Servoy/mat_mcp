/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.mat.snapshot.ISnapshot;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Builds the MCP tool specifications. The tools operate on the heap dump the user has open in the
 * MAT workbench: {@code snapshot_overview} and {@code oql} target the <em>active</em>
 * {@link org.eclipse.mat.ui.snapshot.editor.HeapEditor}'s snapshot, and {@code open_snapshot} opens
 * a dump as a MAT editor tab so it becomes the active snapshot and is visible in the UI.
 *
 * <p>So MCP always acts on "the dump I'm looking at", and all results are returned as JSON text.</p>
 */
public final class MatMcpTools
{
	public MatMcpTools()
	{
	}

	public List<SyncToolSpecification> specifications()
	{
		return List.of(
			openSnapshotTool(),
			snapshotOverviewTool(),
			oqlTool(),
			histogramTool(),
			readObjectTool(),
			arrayElementsTool(),
			outboundReferencesTool(),
			inboundReferencesTool(),
			pathToGcRootsTool(),
			retainedSetTool(),
			topDominatorClassesTool(),
			classDetailsTool(),
			gcRootsTool(),
			threadStacksTool());
	}

	/** Resolves the active snapshot or returns an error result message (never throws). */
	private interface SnapshotFn
	{
		String apply(ISnapshot snapshot) throws Exception;
	}

	/** Shared wrapper: resolve the active snapshot, run fn, map exceptions to a tool error. */
	private static CallToolResult withSnapshot(String toolName, SnapshotFn fn)
	{
		ISnapshot snapshot = ActiveSnapshot.activeSnapshot();
		if (snapshot == null)
		{
			return error(noActiveSnapshotMessage());
		}
		try
		{
			return ok(fn.apply(snapshot));
		}
		catch (Exception e)
		{
			return error(toolName + " failed: " + e.getMessage());
		}
	}

	// ---- open_snapshot --------------------------------------------------------------------

	private SyncToolSpecification openSnapshotTool()
	{
		Tool tool = Tool
			.builder("open_snapshot",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"hprofPath", Map.of(
							"type", "string",
							"description", "Absolute path to the .hprof heap dump to open")),
					"required", List.of("hprofPath")))
			.description(
				"Open a heap dump as a tab in the MAT workbench (it becomes the active snapshot that the other tools query). Returns the heap overview as JSON.")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				String path = stringArg(request.arguments(), "hprofPath");
				try
				{
					ISnapshot snapshot = ActiveSnapshot.openInEditor(path);
					if (snapshot != null)
					{
						return ok(SnapshotJson.overview(snapshot));
					}
					// The editor was requested; MAT parses the dump on a background job and shows a
					// progress bar. The snapshot is not ready yet - tell the caller to poll.
					return ok("{\"status\":\"opening\",\"message\":\"Heap dump is being opened/parsed in MAT. "
						+ "Call snapshot_overview or oql shortly to use it once parsing finishes.\"}");
				}
				catch (Exception e)
				{
					return error("open_snapshot failed: " + e.getMessage());
				}
			})
			.build();
	}

	// ---- snapshot_overview ----------------------------------------------------------------

	private SyncToolSpecification snapshotOverviewTool()
	{
		Tool tool = Tool
			.builder("snapshot_overview", Map.of("type", "object", "properties", Map.of()))
			.description("Return a JSON overview of the active heap-dump editor's snapshot (object/class/GC-root counts, used heap, JVM info).")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				ISnapshot snapshot = ActiveSnapshot.activeSnapshot();
				if (snapshot == null)
				{
					return error(noActiveSnapshotMessage());
				}
				return ok(SnapshotJson.overview(snapshot));
			})
			.build();
	}

	// ---- oql ------------------------------------------------------------------------------

	private SyncToolSpecification oqlTool()
	{
		Tool tool = Tool
			.builder("oql",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"query", Map.of(
							"type", "string",
							"description", "MAT Open Query Language, e.g. SELECT * FROM java.lang.Object[] o WHERE (o.@length > 1000000)"),
						"maxRows", Map.of(
							"type", "integer",
							"description", "Maximum rows / object ids to return (default 200)")),
					"required", List.of("query")))
			.description("Run a MAT OQL query against the active heap-dump editor's snapshot and return rows / object ids as JSON (capped by maxRows).")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				ISnapshot snapshot = ActiveSnapshot.activeSnapshot();
				if (snapshot == null)
				{
					return error(noActiveSnapshotMessage());
				}
				String oql = stringArg(request.arguments(), "query");
				int maxRows = intArg(request.arguments(), "maxRows", 200);
				try
				{
					return ok(OqlRunner.runToJson(snapshot, oql, maxRows));
				}
				catch (Exception e)
				{
					return error("oql failed: " + e.getMessage());
				}
			})
			.build();
	}

	// ---- histogram ------------------------------------------------------------------------

	private SyncToolSpecification histogramTool()
	{
		Tool tool = Tool
			.builder("histogram",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"classNamePattern", Map.of(
							"type", "string",
							"description", "Optional regex to filter class names (e.g. com\\\\.servoy\\\\..*)"),
						"limit", Map.of(
							"type", "integer",
							"description", "Max classes to return (default 50)"))))
			.description("Top classes by shallow heap size and object count (MAT histogram), optionally filtered by a class-name regex.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("histogram",
				snapshot -> InspectionTools.histogram(snapshot, stringArg(request.arguments(), "classNamePattern"),
					intArg(request.arguments(), "limit", 50))))
			.build();
	}

	// ---- read_object ----------------------------------------------------------------------

	private SyncToolSpecification readObjectTool()
	{
		Tool tool = Tool
			.builder("read_object",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectId", Map.of(
							"type", "integer",
							"description", "MAT object id (as returned by oql / other tools)"),
						"maxReferences", Map.of(
							"type", "integer",
							"description", "Max outbound references to list (default 100)")),
					"required", List.of("objectId")))
			.description("Inspect one object: identity, sizes, GC-root status, its instance fields (or static fields for a class) with resolved values, and its named outbound references.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("read_object",
				snapshot -> InspectionTools.readObject(snapshot, requireInt(request.arguments(), "objectId"),
					intArg(request.arguments(), "maxReferences", 100))))
			.build();
	}

	// ---- array_elements -------------------------------------------------------------------

	private SyncToolSpecification arrayElementsTool()
	{
		Tool tool = Tool
			.builder("array_elements",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectId", Map.of(
							"type", "integer",
							"description", "MAT object id of an array object"),
						"from", Map.of(
							"type", "integer",
							"description", "Start index (default 0)"),
						"limit", Map.of(
							"type", "integer",
							"description", "Max elements to return from 'from' (default 100)")),
					"required", List.of("objectId")))
			.description("Elements of an array object: primitive values inline, or object descriptors for a reference array. Windowed by from/limit for large arrays.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("array_elements",
				snapshot -> InspectionTools.arrayElements(snapshot, requireInt(request.arguments(), "objectId"),
					intArg(request.arguments(), "from", 0), intArg(request.arguments(), "limit", 100))))
			.build();
	}

	// ---- outbound_references --------------------------------------------------------------

	private SyncToolSpecification outboundReferencesTool()
	{
		Tool tool = Tool
			.builder("outbound_references",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectId", Map.of("type", "integer", "description", "MAT object id"),
						"limit", Map.of("type", "integer", "description", "Max referents (default 200)")),
					"required", List.of("objectId")))
			.description("The objects this object directly points to (outbound references), as object descriptors.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("outbound_references",
				snapshot -> InspectionTools.outboundReferences(snapshot, requireInt(request.arguments(), "objectId"),
					intArg(request.arguments(), "limit", 200))))
			.build();
	}

	// ---- inbound_references ---------------------------------------------------------------

	private SyncToolSpecification inboundReferencesTool()
	{
		Tool tool = Tool
			.builder("inbound_references",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectId", Map.of("type", "integer", "description", "MAT object id"),
						"limit", Map.of("type", "integer", "description", "Max referrers (default 200)")),
					"required", List.of("objectId")))
			.description("The objects that directly point to this object (inbound references / referrers), as object descriptors.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("inbound_references",
				snapshot -> InspectionTools.inboundReferences(snapshot, requireInt(request.arguments(), "objectId"),
					intArg(request.arguments(), "limit", 200))))
			.build();
	}

	// ---- path_to_gc_roots -----------------------------------------------------------------

	private SyncToolSpecification pathToGcRootsTool()
	{
		Tool tool = Tool
			.builder("path_to_gc_roots",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectId", Map.of("type", "integer", "description", "MAT object id")),
					"required", List.of("objectId")))
			.description("The shortest reference path from the object to a GC root - why the object is kept alive (the retention chain).")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("path_to_gc_roots",
				snapshot -> InspectionTools.pathToGcRoots(snapshot, requireInt(request.arguments(), "objectId"))))
			.build();
	}

	// ---- retained_set ---------------------------------------------------------------------

	private SyncToolSpecification retainedSetTool()
	{
		Tool tool = Tool
			.builder("retained_set",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"objectIds", Map.of(
							"type", "array",
							"items", Map.of("type", "integer"),
							"description", "MAT object ids whose combined retained set to compute")),
					"required", List.of("objectIds")))
			.description("How much heap would be freed if the given objects were garbage collected (retained set size and object count).")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("retained_set",
				snapshot -> InspectionTools.retainedSet(snapshot, requireIntArray(request.arguments(), "objectIds"))))
			.build();
	}

	// ---- top_dominator_classes ------------------------------------------------------------

	private SyncToolSpecification topDominatorClassesTool()
	{
		Tool tool = Tool
			.builder("top_dominator_classes",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"limit", Map.of("type", "integer", "description", "Max classes to return (default 30)"))))
			.description("Classes retaining the most heap (MAT dominator tree grouped by class) - the biggest memory holders.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("top_dominator_classes",
				snapshot -> InspectionTools.topDominatorClasses(snapshot, intArg(request.arguments(), "limit", 30))))
			.build();
	}

	// ---- class_details --------------------------------------------------------------------

	private SyncToolSpecification classDetailsTool()
	{
		Tool tool = Tool
			.builder("class_details",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"name", Map.of("type", "string", "description", "Class name, or a regex if regex=true"),
						"regex", Map.of("type", "boolean", "description", "Treat name as a regex (default false)"),
						"limit", Map.of("type", "integer", "description", "Max classes to return (default 50)")),
					"required", List.of("name")))
			.description("Find classes by name (exact or regex): object counts, per-instance size, superclass and array flag.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("class_details",
				snapshot -> InspectionTools.classDetails(snapshot, stringArg(request.arguments(), "name"),
					boolArg(request.arguments(), "regex", false), intArg(request.arguments(), "limit", 50))))
			.build();
	}

	// ---- gc_roots -------------------------------------------------------------------------

	private SyncToolSpecification gcRootsTool()
	{
		Tool tool = Tool
			.builder("gc_roots",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"limit", Map.of("type", "integer", "description", "Max roots to return (default 200)"))))
			.description("List the GC roots (objects the JVM keeps alive directly) with their class and root type.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("gc_roots",
				snapshot -> InspectionTools.gcRoots(snapshot, intArg(request.arguments(), "limit", 200))))
			.build();
	}

	// ---- thread_stacks --------------------------------------------------------------------

	private SyncToolSpecification threadStacksTool()
	{
		Tool tool = Tool
			.builder("thread_stacks",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"maxLocalsPerFrame", Map.of(
							"type", "integer",
							"description", "Max local objects to list per stack frame (default 20)"))))
			.description("All threads with their stack frames AND the local objects on each frame - exposes the actual objects (arguments/locals) a method holds, which OQL cannot.")
			.build();
		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> withSnapshot("thread_stacks",
				snapshot -> InspectionTools.threadStacks(snapshot, intArg(request.arguments(), "maxLocalsPerFrame", 20))))
			.build();
	}

	// ---- helpers --------------------------------------------------------------------------

	void closeSession()
	{
		// Snapshots are owned by the MAT editors now; nothing to dispose here.
	}

	private static String noActiveSnapshotMessage()
	{
		if (!ActiveSnapshot.isWorkbenchAvailable())
		{
			return "No MAT workbench UI available.";
		}
		return "No heap dump is open in MAT. Open one in the UI, or call open_snapshot with a .hprof path.";
	}

	private static String stringArg(Map<String, Object> args, String name)
	{
		Object v = args == null ? null : args.get(name);
		return v == null ? null : v.toString();
	}

	private static int intArg(Map<String, Object> args, String name, int fallback)
	{
		Object v = args == null ? null : args.get(name);
		if (v instanceof Number n) return n.intValue();
		if (v != null)
		{
			try
			{
				return Integer.parseInt(v.toString().trim());
			}
			catch (NumberFormatException ignore)
			{
				// fall through to default
			}
		}
		return fallback;
	}

	private static int requireInt(Map<String, Object> args, String name)
	{
		Object v = args == null ? null : args.get(name);
		if (v instanceof Number n) return n.intValue();
		if (v != null)
		{
			try
			{
				return Integer.parseInt(v.toString().trim());
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException("'" + name + "' must be an integer");
			}
		}
		throw new IllegalArgumentException("missing required argument '" + name + "'");
	}

	private static boolean boolArg(Map<String, Object> args, String name, boolean fallback)
	{
		Object v = args == null ? null : args.get(name);
		if (v instanceof Boolean b) return b.booleanValue();
		if (v != null) return Boolean.parseBoolean(v.toString().trim());
		return fallback;
	}

	private static int[] requireIntArray(Map<String, Object> args, String name)
	{
		Object v = args == null ? null : args.get(name);
		if (!(v instanceof List< ? > list) || list.isEmpty())
		{
			throw new IllegalArgumentException("'" + name + "' must be a non-empty array of integers");
		}
		int[] out = new int[list.size()];
		for (int i = 0; i < out.length; i++)
		{
			Object e = list.get(i);
			if (e instanceof Number n) out[i] = n.intValue();
			else out[i] = Integer.parseInt(String.valueOf(e).trim());
		}
		return out;
	}

	private static CallToolResult ok(String json)
	{
		return CallToolResult.builder().addTextContent(json).isError(Boolean.FALSE).build();
	}

	private static CallToolResult error(String message)
	{
		return CallToolResult.builder().addTextContent(Json.escape(message)).isError(Boolean.TRUE).build();
	}
}
