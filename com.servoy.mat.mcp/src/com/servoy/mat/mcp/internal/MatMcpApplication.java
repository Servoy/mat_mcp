/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * Headless Equinox application entry point for the MAT MCP server.
 *
 * <p>Launched as:</p>
 *
 * <pre>
 *   eclipsec.exe -application com.servoy.mat.mcp.server &lt;heapdump.hprof&gt;
 * </pre>
 *
 * <p>Skeleton stage: it opens the snapshot once (via {@link SnapshotSession}) and prints the heap
 * overview. The MCP stdio server (io.modelcontextprotocol.sdk) and the tool registry
 * (open_snapshot, oql, dominator_tree, path_to_gc_roots, read_object, thread_stacks, ...) are
 * wired in on top of this open session in the next iteration.</p>
 */
public class MatMcpApplication implements IApplication
{
	@Override
	public Object start(IApplicationContext context) throws Exception
	{
		String[] args = (String[])context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
		String hprofPath = firstHprofArg(args);
		if (hprofPath == null)
		{
			System.err.println("Usage: -application com.servoy.mat.mcp.server <heapdump.hprof>"); //$NON-NLS-1$
			return Integer.valueOf(2);
		}

		System.out.println("[mat-mcp] opening snapshot: " + hprofPath); //$NON-NLS-1$
		long t0 = System.nanoTime();
		try (SnapshotSession session = SnapshotSession.open(hprofPath))
		{
			long ms = (System.nanoTime() - t0) / 1_000_000L;
			System.out.println("[mat-mcp] snapshot opened in " + ms + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
			System.out.println("[mat-mcp] overview: " + session.overviewJson()); //$NON-NLS-1$

			// TODO next iteration: start the MCP stdio server here and keep this session open,
			// registering the tools that query the live ISnapshot. For now we just prove the
			// snapshot opens and report the overview, then exit.
		}
		return IApplication.EXIT_OK;
	}

	@Override
	public void stop()
	{
		// nothing to do yet; the try-with-resources in start() disposes the snapshot
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
