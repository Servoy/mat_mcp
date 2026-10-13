/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal.ui;

import java.util.List;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.menu.MToolItem;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.servoy.mat.mcp.internal.Log;
import com.servoy.mat.mcp.internal.McpServerController;

/**
 * Keeps the toolbar button's icon and tooltip in sync with the real MCP server state by updating the
 * E4 model item directly.
 *
 * <p>MAT runs on the Eclipse 4 workbench, where the classic {@code IElementUpdater} +
 * {@code ICommandService.refreshElements()} path does not drive a toolbar item's icon (measured:
 * {@code updateElement} is never invoked). The reliable approach is to find the item in the live E4
 * model via {@link EModelService} and call {@code setIconURI}/{@code setTooltip} on it - the renderer
 * then repaints it.</p>
 *
 * <p>The item is the one contributed from {@code plugin.xml} with id
 * {@code com.servoy.mat.mcp.toolitem}. All model access is marshalled onto the UI thread.</p>
 */
public final class StatusToolbar
{
	private static final String TOOL_ITEM_ID = "com.servoy.mat.mcp.toolitem"; //$NON-NLS-1$
	private static final String ICON_ON = "platform:/plugin/com.servoy.mat.mcp/icons/mcp-on.svg"; //$NON-NLS-1$
	private static final String ICON_OFF = "platform:/plugin/com.servoy.mat.mcp/icons/mcp-off.svg"; //$NON-NLS-1$

	private StatusToolbar()
	{
	}

	/** Updates the toolbar item to reflect the current server state. Safe to call from any thread. */
	public static void refresh()
	{
		Display display = display();
		if (display == null) return;
		display.asyncExec(StatusToolbar::applyOnUiThread);
	}

	private static void applyOnUiThread()
	{
		try
		{
			MApplication app = PlatformUI.getWorkbench().getService(MApplication.class);
			EModelService modelService = PlatformUI.getWorkbench().getService(EModelService.class);
			if (app == null || modelService == null) return;

			List<MToolItem> items = modelService.findElements(app, TOOL_ITEM_ID, MToolItem.class);
			if (items == null || items.isEmpty())
			{
				// item not in the E4 model yet (e.g. toolbar not rendered); a later refresh will catch it
				return;
			}

			boolean running = McpServerController.getInstance().isRunning();
			String icon = running ? ICON_ON : ICON_OFF;
			String tooltip = running
				? "Stop MCP server (running on " + McpServerController.getInstance().endpointDescription() + ")"
				: "Start MCP server";

			for (MToolItem item : items)
			{
				item.setIconURI(icon);
				item.setTooltip(tooltip);
			}
		}
		catch (Exception e)
		{
			Log.warn("StatusToolbar refresh failed", e); //$NON-NLS-1$
		}
	}

	private static Display display()
	{
		try
		{
			if (!PlatformUI.isWorkbenchRunning()) return null;
			Display d = PlatformUI.getWorkbench().getDisplay();
			return (d != null && !d.isDisposed()) ? d : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
