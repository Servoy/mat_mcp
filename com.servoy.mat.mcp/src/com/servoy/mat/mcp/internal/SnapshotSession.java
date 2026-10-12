/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.io.File;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotInfo;

/**
 * Holds a single parsed MAT heap-dump {@link ISnapshot} open for the lifetime of the MCP session,
 * so the expensive parse/index step runs once and every subsequent query (OQL, dominator tree,
 * path-to-GC-roots, object inspection, thread-stack locals) is a cheap follow-up call against the
 * in-memory snapshot.
 *
 * <p>This is the core difference from launching MAT headless per question: there the snapshot was
 * re-opened on every invocation. Here it stays resident.</p>
 */
public final class SnapshotSession implements AutoCloseable
{
	private final File heapDumpFile;
	private final ISnapshot snapshot;

	private SnapshotSession(File heapDumpFile, ISnapshot snapshot)
	{
		this.heapDumpFile = heapDumpFile;
		this.snapshot = snapshot;
	}

	/**
	 * Opens (parses, building indexes next to the .hprof if they do not yet exist) a heap dump and
	 * keeps it open.
	 *
	 * @param hprofPath absolute path to the .hprof file
	 * @return an open session
	 * @throws Exception if the file is missing or MAT fails to parse it
	 */
	public static SnapshotSession open(String hprofPath) throws Exception
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
		// SnapshotFactory parses the dump and reuses the on-disk indexes when present.
		ISnapshot snapshot = SnapshotFactory.openSnapshot(file, new NullProgressMonitor());
		return new SnapshotSession(file, snapshot);
	}

	public ISnapshot getSnapshot()
	{
		return snapshot;
	}

	public File getHeapDumpFile()
	{
		return heapDumpFile;
	}

	/**
	 * @return a compact, JSON-ready overview of the loaded snapshot
	 */
	public String overviewJson()
	{
		SnapshotInfo info = snapshot.getSnapshotInfo();
		StringBuilder sb = new StringBuilder(256);
		sb.append('{');
		appendString(sb, "heapDumpPath", heapDumpFile.getAbsolutePath()).append(',');
		appendNumber(sb, "numberOfObjects", info.getNumberOfObjects()).append(',');
		appendNumber(sb, "numberOfClasses", info.getNumberOfClasses()).append(',');
		appendNumber(sb, "numberOfClassLoaders", info.getNumberOfClassLoaders()).append(',');
		appendNumber(sb, "numberOfGCRoots", info.getNumberOfGCRoots()).append(',');
		appendNumber(sb, "usedHeapSize", info.getUsedHeapSize()).append(',');
		appendNumber(sb, "identifierSize", info.getIdentifierSize()).append(',');
		appendString(sb, "jvmInfo", info.getJvmInfo());
		sb.append('}');
		return sb.toString();
	}

	private static StringBuilder appendString(StringBuilder sb, String key, String value)
	{
		sb.append('"').append(key).append("\":");
		if (value == null)
		{
			sb.append("null");
		}
		else
		{
			sb.append('"').append(escape(value)).append('"');
		}
		return sb;
	}

	private static StringBuilder appendNumber(StringBuilder sb, String key, long value)
	{
		return sb.append('"').append(key).append("\":").append(value);
	}

	private static String escape(String s)
	{
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			switch (c)
			{
				case '"' :
					out.append("\\\"");
					break;
				case '\\' :
					out.append("\\\\");
					break;
				case '\n' :
					out.append("\\n");
					break;
				case '\r' :
					out.append("\\r");
					break;
				case '\t' :
					out.append("\\t");
					break;
				default :
					out.append(c);
			}
		}
		return out.toString();
	}

	@Override
	public void close()
	{
		if (snapshot != null)
		{
			// releases file handles and frees the in-memory structures
			SnapshotFactory.dispose(snapshot);
		}
	}
}
