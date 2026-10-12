/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.mat.snapshot.ISnapshot;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Builds the MCP tool specifications backed by a single open {@link SnapshotSession}.
 *
 * <p>The session is held in an {@link AtomicReference} so {@code open_snapshot} can (re)point it at
 * a heap dump at run time, and every other tool reads the currently open snapshot. All results are
 * returned as JSON text content.</p>
 */
public final class MatMcpTools
{
	private final AtomicReference<SnapshotSession> session = new AtomicReference<>();

	public MatMcpTools()
	{
	}

	public MatMcpTools(SnapshotSession initial)
	{
		this.session.set(initial);
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
				"Parse and open a heap dump once; the snapshot stays resident so later queries are cheap. Returns the heap overview as JSON.")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				String path = stringArg(request.arguments(), "hprofPath");
				try
				{
					SnapshotSession opened = SnapshotSession.open(path);
					SnapshotSession previous = session.getAndSet(opened);
					if (previous != null)
					{
						previous.close();
					}
					return ok(opened.overviewJson());
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
			.description("Return a JSON overview of the currently open snapshot (object/class/GC-root counts, used heap, JVM info).")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				SnapshotSession current = session.get();
				if (current == null)
				{
					return error("No snapshot open. Call open_snapshot first.");
				}
				return ok(current.overviewJson());
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
			.description("Run a MAT OQL query against the open snapshot and return rows / object ids as JSON (capped by maxRows).")
			.build();

		return SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				SnapshotSession current = session.get();
				if (current == null)
				{
					return error("No snapshot open. Call open_snapshot first.");
				}
				String oql = stringArg(request.arguments(), "query");
				int maxRows = intArg(request.arguments(), "maxRows", 200);
				try
				{
					ISnapshot snapshot = current.getSnapshot();
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

	SnapshotSession currentSession()
	{
		return session.get();
	}

	void closeSession()
	{
		SnapshotSession current = session.getAndSet(null);
		if (current != null)
		{
			current.close();
		}
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
