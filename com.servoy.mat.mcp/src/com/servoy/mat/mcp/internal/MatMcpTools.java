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
		return List.of(openSnapshotTool(), snapshotOverviewTool(), oqlTool());
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

	private static CallToolResult ok(String json)
	{
		return CallToolResult.builder().addTextContent(json).isError(Boolean.FALSE).build();
	}

	private static CallToolResult error(String message)
	{
		return CallToolResult.builder().addTextContent(Json.escape(message)).isError(Boolean.TRUE).build();
	}
}
