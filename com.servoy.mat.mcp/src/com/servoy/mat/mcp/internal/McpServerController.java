/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

/**
 * Process-wide singleton that owns the one {@link McpHttpServer} and the one resident
 * {@link MatMcpTools} session, so both the auto-start DS component and the UI toggle command act on
 * the same server instance.
 *
 * <p>Start/stop are idempotent and synchronized. {@link #isRunning()} backs the toolbar/menu toggle
 * state and only reports {@code true} once the HTTP port is actually bound, so a failed start does
 * not masquerade as running.</p>
 */
public final class McpServerController
{
	private static final McpServerController INSTANCE = new McpServerController();

	public static McpServerController getInstance()
	{
		return INSTANCE;
	}

	private final MatMcpTools tools = new MatMcpTools();
	private McpHttpServer httpServer;
	private volatile Throwable lastError;

	private McpServerController()
	{
	}

	public synchronized boolean isRunning()
	{
		return httpServer != null;
	}

	public Throwable getLastError()
	{
		return lastError;
	}

	/**
	 * Starts the MCP HTTP server if it is not already running. The port bind runs synchronously here
	 * (callers already hand this off to a background thread where needed) so the caller learns the
	 * real outcome and {@link #isRunning()} is only true after a successful bind.
	 *
	 * @throws Exception if the server fails to start (e.g. the port is already in use)
	 */
	public synchronized void start() throws Exception
	{
		if (httpServer != null) return;
		lastError = null;
		McpHttpServer server = new McpHttpServer(tools);
		try
		{
			server.start();
			httpServer = server;
		}
		catch (Exception e)
		{
			lastError = e;
			Log.error("MCP HTTP server failed to start", e); //$NON-NLS-1$
			try
			{
				server.stop();
			}
			catch (RuntimeException ignore)
			{
				// best-effort cleanup
			}
			throw e;
		}
	}

	/**
	 * Stops the MCP HTTP server and disposes the open snapshot, if running.
	 */
	public synchronized void stop()
	{
		if (httpServer != null)
		{
			httpServer.stop();
			httpServer = null;
		}
		tools.closeSession();
	}

	/**
	 * @return the host:port/endpoint the server listens on, for status display
	 */
	public String endpointDescription()
	{
		String host = System.getProperty(McpHttpServer.PROP_HOST, McpHttpServer.DEFAULT_HOST);
		int port = Integer.getInteger(McpHttpServer.PROP_PORT, McpHttpServer.DEFAULT_PORT).intValue();
		return "http://" + host + ':' + port + McpHttpServer.ENDPOINT; //$NON-NLS-1$
	}
}
