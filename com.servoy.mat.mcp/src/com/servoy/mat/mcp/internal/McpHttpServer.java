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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
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
 *
 * <p>Logging goes through SLF4J (shipped with MAT), so start-up and failures land in MAT's normal
 * log pipeline instead of a hand-rolled file.</p>
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
		if (jetty != null)
		{
			Log.info("MCP HTTP server already running; start() ignored"); //$NON-NLS-1$
			return;
		}

		String host = System.getProperty(PROP_HOST, DEFAULT_HOST);
		int port = Integer.getInteger(PROP_PORT, DEFAULT_PORT).intValue();
		Log.info("Starting MCP Streamable HTTP server on http://" + host + ':' + port + ENDPOINT); //$NON-NLS-1$

		// Construct the Jackson-backed mapper directly instead of McpJsonDefaults.getMapper().
		// getMapper() resolves the mapper through a ServiceLoader / SCR-registered supplier, which
		// in this OSGi runtime is only present once mcp-json-jackson2's DS component has been
		// activated - a startup-ordering race that threw "No McpJsonMapperSupplier available". We
		// own the jackson2 binding on our class path, so build it ourselves and avoid the race.
		McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
		// Same reason as the mapper: McpServer.build() otherwise calls McpJsonDefaults.getSchemaValidator(),
		// which resolves via the SCR/ServiceLoader supplier that is not registered in this runtime
		// ("No JsonSchemaValidatorSupplier available"). Supply the Jackson validator explicitly.
		JsonSchemaValidator schemaValidator = new DefaultJsonSchemaValidator(new ObjectMapper());

		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(jsonMapper)
			.mcpEndpoint(ENDPOINT)
			.build();

		mcpServer = McpServer.sync(transport)
			.serverInfo("mat-mcp", "1.0.0") //$NON-NLS-1$ //$NON-NLS-2$
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.jsonMapper(jsonMapper)
			.jsonSchemaValidator(schemaValidator)
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

		Log.info("MCP Streamable HTTP server listening on http://" + host + ':' + port + ENDPOINT); //$NON-NLS-1$
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
			Log.warn("Error closing MCP server", e); //$NON-NLS-1$
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
				Log.info("MCP HTTP server stopped"); //$NON-NLS-1$
			}
		}
		catch (Exception e)
		{
			Log.warn("Error stopping Jetty", e); //$NON-NLS-1$
		}
		finally
		{
			jetty = null;
		}
	}
}
