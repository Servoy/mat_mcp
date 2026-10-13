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
 * <p>The button's icon and tooltip are kept in sync with the server state by {@link StatusToolbar}
 * (which updates the live E4 model item), not by {@code IElementUpdater} - that is not invoked in the
 * E4 workbench MAT runs on. After every start/stop we call {@link StatusToolbar#refresh()}.</p>
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
		}
		else
		{
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
		}
		StatusToolbar.refresh();
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
