/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator that auto-starts the MCP HTTP server when the bundle is activated inside MAT, so
 * the server is reachable as soon as MAT is running without any manual step.
 *
 * <p>No heap dump is opened at activation; the snapshot is opened lazily through the
 * {@code open_snapshot} tool once a client connects and asks for one. Startup is done on a
 * background thread so bundle activation (and MAT's own startup) is never blocked by binding the
 * HTTP port.</p>
 */
public class Activator implements BundleActivator
{
	public static final String PLUGIN_ID = "com.servoy.mat.mcp"; //$NON-NLS-1$

	private static BundleContext context;

	private final MatMcpTools tools = new MatMcpTools();
	private McpHttpServer httpServer;

	public static BundleContext getContext()
	{
		return context;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception
	{
		Activator.context = bundleContext;
		httpServer = new McpHttpServer(tools);
		Thread starter = new Thread(() -> {
			try
			{
				httpServer.start();
			}
			catch (Exception e)
			{
				System.err.println("[mat-mcp] failed to start MCP HTTP server: " + e.getMessage()); //$NON-NLS-1$
				e.printStackTrace();
			}
		}, "mat-mcp-http-starter"); //$NON-NLS-1$
		starter.setDaemon(true);
		starter.start();
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception
	{
		try
		{
			if (httpServer != null)
			{
				httpServer.stop();
			}
			tools.closeSession();
		}
		finally
		{
			httpServer = null;
			Activator.context = null;
		}
	}
}
