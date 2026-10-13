/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.servoy.mat.mcp.internal.Log;
import com.servoy.mat.mcp.internal.McpServerController;

/**
 * Starts the MCP HTTP server automatically when the MAT workbench starts, via the
 * {@code org.eclipse.ui.startup} extension point.
 *
 * <p>This replaces the earlier Declarative-Services auto-start, which was unreliable: nothing in MAT
 * references our bundle, so SCR often never activated it. The {@code org.eclipse.ui.startup}
 * extension is instead guaranteed to be invoked by the workbench on every launch, which both runs
 * this code and loads our bundle - so the server comes up without any manual action.</p>
 *
 * <p>We wait for the workbench window to exist (retrying on the UI thread) before starting, then
 * hand the actual port bind to {@link McpServerController} (which binds on a background thread). The
 * UI toggle command acts on the same controller.</p>
 */
public class StartupMcpServer implements IStartup
{
	@Override
	public void earlyStartup()
	{
		Display.getDefault().asyncExec(this::waitForWorkbench);
	}

	private void waitForWorkbench()
	{
		try
		{
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null)
			{
				// Bind the port off the UI thread so startup is not blocked, then update the toolbar
				// icon back on the UI thread once we know the real running state. A small extra delay
				// before the refresh gives the workbench time to render the toolbar button, so the
				// IElementUpdater is actually consulted (an immediate refresh can land before the
				// button exists, leaving a stale grey icon).
				Thread starter = new Thread(() -> {
					try
					{
						McpServerController.getInstance().start();
					}
					catch (Exception e)
					{
						Log.error("Auto-start of MCP HTTP server failed", e); //$NON-NLS-1$
					}
					Display.getDefault().asyncExec(
						() -> Display.getDefault().timerExec(500, StatusToolbar::refresh));
				}, "mat-mcp-http-starter"); //$NON-NLS-1$
				starter.setDaemon(true);
				starter.start();
			}
			else
			{
				// window not active yet; retry shortly
				Display.getDefault().timerExec(500, this::waitForWorkbench);
			}
		}
		catch (IllegalStateException notReadyYet)
		{
			// workbench not created yet; retry shortly
			Display.getDefault().timerExec(500, this::waitForWorkbench);
		}
	}
}
