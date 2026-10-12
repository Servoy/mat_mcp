/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.util.concurrent.CountDownLatch;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Headless Equinox application entry point for the MAT MCP server.
 *
 * <p>Launched as:</p>
 *
 * <pre>
 *   eclipsec.exe -application com.servoy.mat.mcp.server [heapdump.hprof]
 * </pre>
 *
 * <p>It starts an MCP server over stdio and keeps running until the stdin stream closes (the MCP
 * client disconnects). The MAT snapshot is opened once — either eagerly from a .hprof passed on the
 * command line, or lazily via the {@code open_snapshot} tool — and stays resident so OQL and the
 * other tools query the same in-memory {@code ISnapshot}.</p>
 *
 * <p>stdout is reserved for the MCP JSON-RPC protocol, so all diagnostics go to stderr.</p>
 */
public class MatMcpApplication implements IApplication
{
	private volatile McpSyncServer server;
	private final CountDownLatch shutdown = new CountDownLatch(1);

	@Override
	public Object start(IApplicationContext context) throws Exception
	{
		String[] args = (String[])context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
		String hprofPath = firstHprofArg(args);

		MatMcpTools tools;
		if (hprofPath != null)
		{
			System.err.println("[mat-mcp] opening snapshot: " + hprofPath); //$NON-NLS-1$
			long t0 = System.nanoTime();
			SnapshotSession eager = SnapshotSession.open(hprofPath);
			long ms = (System.nanoTime() - t0) / 1_000_000L;
			System.err.println("[mat-mcp] snapshot opened in " + ms + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
			tools = new MatMcpTools(eager);
		}
		else
		{
			System.err.println("[mat-mcp] no heap dump on command line; waiting for open_snapshot tool call"); //$NON-NLS-1$
			tools = new MatMcpTools();
		}

		StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

		server = McpServer.sync(transport)
			.serverInfo("mat-mcp", "1.0.0") //$NON-NLS-1$ //$NON-NLS-2$
			.capabilities(ServerCapabilities.builder()
				.tools(true)
				.build())
			.tools(tools.specifications().toArray(new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification[0]))
			.build();

		System.err.println("[mat-mcp] MCP stdio server ready"); //$NON-NLS-1$

		// Block the application thread until stop() is called (stdin closed / platform shutdown).
		shutdown.await();

		tools.closeSession();
		return IApplication.EXIT_OK;
	}

	@Override
	public void stop()
	{
		try
		{
			McpSyncServer s = server;
			if (s != null)
			{
				s.closeGracefully();
			}
		}
		finally
		{
			shutdown.countDown();
		}
	}

	private static String firstHprofArg(String[] args)
	{
		if (args == null) return null;
		for (String a : args)
		{
			if (a != null && a.toLowerCase().endsWith(".hprof")) //$NON-NLS-1$
			{
				return a;
			}
		}
		return null;
	}
}
