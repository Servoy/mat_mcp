/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;

/**
 * Shared helpers for rendering MAT objects as JSON across the tools.
 *
 * <p>Every object is identified by both its {@code objectId} (MAT's internal int handle, stable for
 * the life of the snapshot and what the other tools accept) and its hex {@code address}, plus a
 * human label and class name, so an AI client can both display and chain calls.</p>
 */
public final class ObjectJson
{
	private ObjectJson()
	{
	}

	/** Appends a compact object descriptor object: {objectId, address, class, name, shallow, retained}. */
	public static void appendObject(StringBuilder sb, ISnapshot snapshot, int objectId)
	{
		sb.append('{');
		sb.append("\"objectId\":").append(objectId);
		try
		{
			IObject o = snapshot.getObject(objectId);
			sb.append(",\"address\":").append(Json.escape(toHex(o.getObjectAddress())));
			sb.append(",\"class\":").append(Json.escape(o.getClazz() != null ? o.getClazz().getName() : "?"));
			sb.append(",\"name\":").append(Json.escape(o.getDisplayName()));
			sb.append(",\"shallowSize\":").append(o.getUsedHeapSize());
			sb.append(",\"retainedSize\":").append(o.getRetainedHeapSize());
		}
		catch (Exception e)
		{
			sb.append(",\"error\":").append(Json.escape(e.getClass().getSimpleName() + ": " + e.getMessage()));
		}
		sb.append('}');
	}

	/** Appends an array of object descriptors for the given ids, capped at {@code limit}. */
	public static void appendObjectArray(StringBuilder sb, ISnapshot snapshot, int[] ids, int limit)
	{
		int shown = Math.min(ids.length, limit);
		sb.append('[');
		for (int i = 0; i < shown; i++)
		{
			if (i > 0) sb.append(',');
			appendObject(sb, snapshot, ids[i]);
		}
		sb.append(']');
	}

	public static String toHex(long address)
	{
		return "0x" + Long.toHexString(address);
	}
}
