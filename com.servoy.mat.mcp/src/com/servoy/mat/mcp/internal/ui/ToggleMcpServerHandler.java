/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.servoy.mat.mcp.internal.McpServerController;

/**
 * Toolbar/menu command handler that toggles the MCP HTTP server on and off.
 *
 * <p>Having this handler in our own bundle is also what guarantees the bundle is started by the
 * workbench: as soon as the UI needs to render / enable the command it loads this class, which
 * activates the bundle (so even if auto-start via SCR did not run, clicking the button brings the
 * server up).</p>
 *
 * <p>The handler reports the honest outcome: if the port bind fails, the user sees the real error
 * (which is also logged via SLF4J to MAT's log), not a false "started" message.</p>
 */
public class ToggleMcpServerHandler extends AbstractHandler
{
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException
	{
		Shell shell = HandlerUtil.getActiveShell(event);
		McpServerController controller = McpServerController.getInstance();

		if (controller.isRunning())
		{
			controller.stop();
			info(shell, "MCP server stopped.");
			return null;
		}

		try
		{
			controller.start();
			info(shell, "MCP server running on " + controller.endpointDescription());
		}
		catch (Exception e)
		{
			error(shell, "MCP server failed to start:\n" + e.getClass().getSimpleName() + ": " + e.getMessage() +
				"\n\nSee the MAT error log for the full stack trace.");
		}
		return null;
	}

	private static void info(Shell shell, String message)
	{
		if (shell != null)
		{
			MessageDialog.openInformation(shell, "MAT MCP Server", message);
		}
	}

	private static void error(Shell shell, String message)
	{
		if (shell != null)
		{
			MessageDialog.openError(shell, "MAT MCP Server", message);
		}
	}
}
