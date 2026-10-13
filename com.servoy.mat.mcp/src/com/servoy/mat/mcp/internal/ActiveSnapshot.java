/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

/**
 * Bridges the MCP tools (which run off the UI thread) to the heap dump the user has open in the MAT
 * workbench.
 *
 * <p>MAT's heap editor types ({@code HeapEditor}, {@code ISnapshotEditorInput}) live in the
 * <strong>bundle-private</strong> package {@code org.eclipse.mat.ui.snapshot.editor} - MAT does not
 * export it at all, so no OSGi import (normal, dynamic, or x-friends) can make those types loadable
 * from our bundle. We therefore never reference them directly; instead we take the active
 * {@link IEditorPart} (which MAT itself has already loaded with its own class loader) and call its
 * {@code getSnapshotInput().getSnapshot()} chain <em>reflectively</em>. The returned
 * {@link ISnapshot} is from an exported MAT API package, so we can use it normally.</p>
 *
 * <p>{@code open_snapshot} opens the dump as a MAT editor tab via the public {@link IDE} API; the
 * tools then read whichever heap editor is active.</p>
 */
public final class ActiveSnapshot
{
	private static final String HEAP_EDITOR_CLASS = "org.eclipse.mat.ui.snapshot.editor.HeapEditor"; //$NON-NLS-1$
	private static final String HEAP_EDITOR_ID = "org.eclipse.mat.ui.editors.HeapEditor"; //$NON-NLS-1$

	private ActiveSnapshot()
	{
	}

	/**
	 * @return true when an Eclipse/MAT workbench UI is available (false in headless mode)
	 */
	public static boolean isWorkbenchAvailable()
	{
		try
		{
			return PlatformUI.isWorkbenchRunning();
		}
		catch (Throwable t)
		{
			return false;
		}
	}

	/**
	 * Resolves the {@link ISnapshot} of the active heap-dump editor, or null if there is none.
	 */
	public static ISnapshot activeSnapshot()
	{
		if (!isWorkbenchAvailable()) return null;
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (display == null || display.isDisposed()) return null;

		AtomicReference<ISnapshot> result = new AtomicReference<>();
		display.syncExec(() -> result.set(readActiveSnapshot()));
		return result.get();
	}

	/**
	 * Opens the given .hprof as a MAT heap editor tab (asynchronously). Returns the snapshot if it
	 * happens to be ready already (usually not - MAT parses on a background job), otherwise null;
	 * callers poll {@link #activeSnapshot()}.
	 */
	public static ISnapshot openInEditor(String hprofPath) throws Exception
	{
		if (hprofPath == null || hprofPath.isBlank())
		{
			throw new IllegalArgumentException("hprofPath must not be empty"); //$NON-NLS-1$
		}
		File file = new File(hprofPath);
		if (!file.isFile())
		{
			throw new IllegalArgumentException("Heap dump not found: " + file.getAbsolutePath()); //$NON-NLS-1$
		}
		if (!isWorkbenchAvailable())
		{
			throw new IllegalStateException("No MAT workbench UI available to open the heap dump in."); //$NON-NLS-1$
		}

		AtomicReference<Exception> failure = new AtomicReference<>();
		Display display = PlatformUI.getWorkbench().getDisplay();
		// Request the open asynchronously; MAT parses on its own Job. Blocking the HTTP worker inside
		// syncExec until the parse finishes could deadlock against the UI thread / modal progress.
		display.asyncExec(() -> {
			try
			{
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				IWorkbenchPage page = window != null ? window.getActivePage() : null;
				if (page == null)
				{
					failure.set(new IllegalStateException("No active workbench page")); //$NON-NLS-1$
					return;
				}
				IDE.openEditor(page, file.toURI(), HEAP_EDITOR_ID, true);
			}
			catch (Exception e)
			{
				failure.set(e);
				Log.error("Failed to open heap dump editor for " + file.getAbsolutePath(), e); //$NON-NLS-1$
			}
		});
		Thread.sleep(200);
		if (failure.get() != null) throw failure.get();
		return activeSnapshot();
	}

	// ---- reflection over MAT's bundle-private HeapEditor -----------------------------------

	/**
	 * Must run on the UI thread. Finds the active (or first) heap editor and reflectively extracts its
	 * {@link ISnapshot}.
	 */
	private static ISnapshot readActiveSnapshot()
	{
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window != null ? window.getActivePage() : null;
		if (page == null) return null;

		IEditorPart active = page.getActiveEditor();
		ISnapshot fromActive = snapshotOf(active);
		if (fromActive != null) return fromActive;

		// active part is not a (loaded) heap editor; try any open heap editor
		for (IEditorReference ref : page.getEditorReferences())
		{
			ISnapshot s = snapshotOf(ref.getEditor(false));
			if (s != null) return s;
		}
		return null;
	}

	/**
	 * Returns the {@link ISnapshot} held by the given editor part if it is a MAT HeapEditor, via
	 * reflection (the HeapEditor / ISnapshotEditorInput types are in a non-exported MAT package).
	 */
	private static ISnapshot snapshotOf(IEditorPart part)
	{
		if (part == null) return null;
		if (!isHeapEditor(part.getClass())) return null;
		try
		{
			// HeapEditor.getSnapshotInput() is itself declared on the (bundle-private) HeapEditor
			// class, but it is reachable because we found the part via the public IEditorPart. Look
			// the method up walking up to a public declaring type to avoid IllegalAccessException on
			// the concrete subclass.
			Method getSnapshotInput = publicMethod(part.getClass(), "getSnapshotInput"); //$NON-NLS-1$
			if (getSnapshotInput == null) return null;
			Object input = getSnapshotInput.invoke(part);
			if (input == null) return null;

			// input's concrete type is HeapEditor$SnapshotEditorInput, which is NOT accessible to us;
			// resolve hasSnapshot()/getSnapshot() through its public ISnapshotEditorInput interface so
			// the reflective call is permitted.
			Method hasSnapshot = publicMethod(input.getClass(), "hasSnapshot"); //$NON-NLS-1$
			Method getSnapshot = publicMethod(input.getClass(), "getSnapshot"); //$NON-NLS-1$
			if (hasSnapshot == null || getSnapshot == null) return null;

			Object has = hasSnapshot.invoke(input);
			if (!(has instanceof Boolean) || !((Boolean)has).booleanValue()) return null;

			Object snapshot = getSnapshot.invoke(input);
			return (snapshot instanceof ISnapshot) ? (ISnapshot)snapshot : null;
		}
		catch (ReflectiveOperationException e)
		{
			Log.warn("Could not read snapshot from active editor via reflection", e); //$NON-NLS-1$
			return null;
		}
	}

	/**
	 * Finds a no-arg method by name, declared on a <em>public</em> type (class or interface) in the
	 * given type's hierarchy, so that reflective invocation is not blocked by a non-public concrete
	 * implementation class (MAT's HeapEditor / SnapshotEditorInput are bundle-private).
	 */
	private static Method publicMethod(Class< ? > type, String name)
	{
		for (Class< ? > c = type; c != null; c = c.getSuperclass())
		{
			Method m = declaredPublic(c, name);
			if (m != null) return m;
			for (Class< ? > iface : c.getInterfaces())
			{
				Method im = declaredPublic(iface, name);
				if (im != null) return im;
			}
		}
		return null;
	}

	private static Method declaredPublic(Class< ? > c, String name)
	{
		if (!java.lang.reflect.Modifier.isPublic(c.getModifiers())) return null;
		try
		{
			return c.getMethod(name);
		}
		catch (NoSuchMethodException e)
		{
			return null;
		}
	}

	private static boolean isHeapEditor(Class< ? > c)
	{
		for (Class< ? > k = c; k != null; k = k.getSuperclass())
		{
			if (HEAP_EDITOR_CLASS.equals(k.getName())) return true;
		}
		return false;
	}
}
