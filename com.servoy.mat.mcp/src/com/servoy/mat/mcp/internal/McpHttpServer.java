/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Hosts the MAT MCP server over HTTP (MCP Streamable HTTP) using an embedded Jetty running inside
 * the MAT JVM. HTTP is used rather than stdio because the server lives for the whole life of the
 * MAT process (so the parsed snapshot stays resident) and an MCP client connects to it by URL,
 * instead of the client spawning a short-lived process per session.
 *
 * <p>The endpoint is {@code http://<host>:<port>/mcp}. Host and port default to {@code 127.0.0.1}
 * and {@code 8923} and can be overridden with the system properties {@code mat.mcp.host} and
 * {@code mat.mcp.port}.</p>
 */
public final class McpHttpServer
{
	public static final String PROP_HOST = "mat.mcp.host"; //$NON-NLS-1$
	public static final String PROP_PORT = "mat.mcp.port"; //$NON-NLS-1$
	public static final String DEFAULT_HOST = "127.0.0.1"; //$NON-NLS-1$
	public static final int DEFAULT_PORT = 8923;
	public static final String ENDPOINT = "/mcp"; //$NON-NLS-1$

	private final MatMcpTools tools;
	private Server jetty;
	private McpSyncServer mcpServer;

	public McpHttpServer(MatMcpTools tools)
	{
		this.tools = tools;
	}

	public synchronized void start() throws Exception
	{
		if (jetty != null) return;

		String host = System.getProperty(PROP_HOST, DEFAULT_HOST);
		int port = Integer.getInteger(PROP_PORT, DEFAULT_PORT).intValue();

		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(McpJsonDefaults.getMapper())
			.mcpEndpoint(ENDPOINT)
			.build();

		mcpServer = McpServer.sync(transport)
			.serverInfo("mat-mcp", "1.0.0") //$NON-NLS-1$ //$NON-NLS-2$
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tools.specifications().toArray(new SyncToolSpecification[0]))
			.build();

		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setHost(host);
		connector.setPort(port);
		server.addConnector(connector);

		ServletContextHandler contextHandler = new ServletContextHandler();
		contextHandler.setContextPath("/"); //$NON-NLS-1$
		// the MCP transport provider is itself an HttpServlet
		contextHandler.addServlet(new ServletHolder(transport), ENDPOINT);
		server.setHandler(contextHandler);

		server.start();
		this.jetty = server;

		System.err.println("[mat-mcp] MCP Streamable HTTP server listening on http://" + host + ':' + port + ENDPOINT); //$NON-NLS-1$
	}

	public synchronized void stop()
	{
		try
		{
			if (mcpServer != null)
			{
				mcpServer.closeGracefully();
			}
		}
		catch (RuntimeException e)
		{
			System.err.println("[mat-mcp] error closing MCP server: " + e.getMessage()); //$NON-NLS-1$
		}
		finally
		{
			mcpServer = null;
		}
		try
		{
			if (jetty != null)
			{
				jetty.stop();
			}
		}
		catch (Exception e)
		{
			System.err.println("[mat-mcp] error stopping Jetty: " + e.getMessage()); //$NON-NLS-1$
		}
		finally
		{
			jetty = null;
		}
	}
}
