/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotInfo;

/**
 * Renders an {@link ISnapshot}'s high-level info as a compact JSON object, independent of how the
 * snapshot was obtained (MAT editor or headless parse).
 */
public final class SnapshotJson
{
	private SnapshotJson()
	{
	}

	public static String overview(ISnapshot snapshot)
	{
		SnapshotInfo info = snapshot.getSnapshotInfo();
		StringBuilder sb = new StringBuilder(256);
		sb.append('{');
		appendString(sb, "heapDumpPath", info.getPath()).append(',');
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
		return sb.append(Json.escape(key)).append(':').append(Json.value(value));
	}

	private static StringBuilder appendNumber(StringBuilder sb, String key, long value)
	{
		return sb.append(Json.escape(key)).append(':').append(value);
	}
}
