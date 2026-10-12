/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Minimal bundle activator. The real work happens in {@link MatMcpApplication}, started as a
 * headless Equinox application; this activator only tracks the bundle context so services can be
 * looked up if needed later.
 */
public class Activator implements BundleActivator
{
	public static final String PLUGIN_ID = "com.servoy.mat.mcp"; //$NON-NLS-1$

	private static BundleContext context;

	public static BundleContext getContext()
	{
		return context;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception
	{
		Activator.context = bundleContext;
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception
	{
		Activator.context = null;
	}
}
