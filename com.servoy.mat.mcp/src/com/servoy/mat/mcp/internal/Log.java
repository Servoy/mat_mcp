/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Thin logging facade that writes to the Eclipse platform log of this bundle.
 *
 * <p>Entries therefore land in the workspace log ({@code .metadata/.log}) and the MAT "Error Log"
 * view, which is where a MAT user actually looks - unlike SLF4J, which MAT routes to
 * {@code slf4j.simple} on stderr and is invisible in a normally-launched MAT.</p>
 */
public final class Log
{
	private static final String PLUGIN_ID = "com.servoy.mat.mcp"; //$NON-NLS-1$

	private Log()
	{
	}

	private static ILog log()
	{
		Bundle bundle = FrameworkUtil.getBundle(Log.class);
		return bundle != null ? Platform.getLog(bundle) : null;
	}

	public static void info(String message)
	{
		ILog l = log();
		if (l != null) l.log(new Status(IStatus.INFO, PLUGIN_ID, message));
	}

	public static void warn(String message, Throwable t)
	{
		ILog l = log();
		if (l != null) l.log(new Status(IStatus.WARNING, PLUGIN_ID, message, t));
	}

	public static void error(String message, Throwable t)
	{
		ILog l = log();
		if (l != null) l.log(new Status(IStatus.ERROR, PLUGIN_ID, message, t));
	}
}
