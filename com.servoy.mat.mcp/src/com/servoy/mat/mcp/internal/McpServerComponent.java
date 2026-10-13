/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

/**
 * Declarative Services (SCR) component that auto-starts the MCP HTTP server as soon as the bundle is
 * activated inside MAT, delegating to the shared {@link McpServerController} so the UI toggle acts
 * on the same server instance.
 *
 * <p>SCR (org.apache.felix.scr, shipped with MAT) activates an {@code immediate=true} component on
 * resolution. The component is wired purely through
 * {@code OSGI-INF/...McpServerComponent.xml}; the {@code org.osgi.service.component.annotations}
 * package is not used because MAT does not export it.</p>
 *
 * <p>The actual bind is pushed onto a daemon thread so SCR activation (and MAT startup) is never
 * blocked by binding the HTTP port.</p>
 */
public class McpServerComponent
{
	public void activate()
	{
		Thread starter = new Thread(() -> {
			try
			{
				McpServerController.getInstance().start();
			}
			catch (Exception e)
			{
				Log.error("Auto-start of MCP HTTP server failed", e); //$NON-NLS-1$
			}
		}, "mat-mcp-http-starter"); //$NON-NLS-1$
		starter.setDaemon(true);
		starter.start();
	}

	public void deactivate()
	{
		McpServerController.getInstance().stop();
	}
}
