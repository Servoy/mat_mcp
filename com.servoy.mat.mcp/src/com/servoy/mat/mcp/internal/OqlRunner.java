/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.util.VoidProgressListener;

/**
 * Runs a MAT Open Query Language (OQL) query against an open {@link ISnapshot} and renders the
 * result as JSON.
 *
 * <p>MAT's {@link IOQLQuery#execute} can return one of three shapes, all handled here:</p>
 * <ul>
 * <li>an {@link IResultTable} / {@link IStructuredResult} (the usual {@code SELECT ...} with named
 * columns) — rendered as {@code {"columns":[...], "rows":[[...], ...]}};</li>
 * <li>an {@code int[]} of object ids (a {@code SELECT *}/object-list form) — rendered as
 * {@code {"objectIds":[...]}} with a bounded preview;</li>
 * <li>any other object — rendered as a single {@code {"value": "..."}}.</li>
 * </ul>
 *
 * <p>Rows are capped by {@code maxRows} so a query that matches millions of objects cannot flood
 * the MCP response; the JSON always reports the real total and whether it was truncated.</p>
 */
public final class OqlRunner
{
	private OqlRunner()
	{
	}

	public static String runToJson(ISnapshot snapshot, String oql, int maxRows) throws Exception
	{
		if (oql == null || oql.isBlank())
		{
			throw new IllegalArgumentException("oql query must not be empty"); //$NON-NLS-1$
		}
		int limit = maxRows > 0 ? maxRows : 200;

		IOQLQuery query = SnapshotFactory.createQuery(oql);
		Object result = query.execute(snapshot, new VoidProgressListener());

		if (result == null)
		{
			return "{\"query\":" + Json.escape(oql) + ",\"empty\":true}";
		}
		if (result instanceof int[] ids)
		{
			return objectIdsToJson(oql, ids, limit);
		}
		if (result instanceof IResultTable table)
		{
			return tableToJson(oql, table, table.getRowCount(), limit);
		}
		if (result instanceof IStructuredResult structured)
		{
			// Some OQL results implement IStructuredResult without IResultTable row access; fall
			// back to describing the columns and the scalar rendering.
			return structuredToJson(oql, structured);
		}
		if (result instanceof IResult)
		{
			return "{\"query\":" + Json.escape(oql) + ",\"value\":" + Json.escape(result.getClass().getName()) + "}";
		}
		return "{\"query\":" + Json.escape(oql) + ",\"value\":" + Json.value(result) + "}";
	}

	private static String objectIdsToJson(String oql, int[] ids, int limit)
	{
		int shown = Math.min(ids.length, limit);
		StringBuilder sb = new StringBuilder(64 + shown * 12);
		sb.append("{\"query\":").append(Json.escape(oql));
		sb.append(",\"objectCount\":").append(ids.length);
		sb.append(",\"truncated\":").append(ids.length > shown);
		sb.append(",\"objectIds\":[");
		for (int i = 0; i < shown; i++)
		{
			if (i > 0) sb.append(',');
			sb.append(ids[i]);
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String tableToJson(String oql, IResultTable table, int rowCount, int limit)
	{
		Column[] columns = table.getColumns();
		int shown = Math.min(rowCount, limit);

		StringBuilder sb = new StringBuilder(256);
		sb.append("{\"query\":").append(Json.escape(oql));
		sb.append(",\"rowCount\":").append(rowCount);
		sb.append(",\"truncated\":").append(rowCount > shown);
		sb.append(",\"columns\":[");
		for (int c = 0; c < columns.length; c++)
		{
			if (c > 0) sb.append(',');
			sb.append(Json.escape(columns[c].getLabel()));
		}
		sb.append("],\"rows\":[");
		for (int r = 0; r < shown; r++)
		{
			Object row = table.getRow(r);
			if (r > 0) sb.append(',');
			sb.append('[');
			for (int c = 0; c < columns.length; c++)
			{
				if (c > 0) sb.append(',');
				Object cell = safeColumnValue(table, row, c);
				sb.append(Json.value(cell));
			}
			sb.append(']');
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String structuredToJson(String oql, IStructuredResult structured)
	{
		Column[] columns = structured.getColumns();
		StringBuilder sb = new StringBuilder(128);
		sb.append("{\"query\":").append(Json.escape(oql));
		sb.append(",\"columns\":[");
		for (int c = 0; c < columns.length; c++)
		{
			if (c > 0) sb.append(',');
			sb.append(Json.escape(columns[c].getLabel()));
		}
		sb.append("],\"note\":\"structured result without row access; use a SELECT form for rows\"}");
		return sb.toString();
	}

	private static Object safeColumnValue(IStructuredResult result, Object row, int column)
	{
		try
		{
			return result.getColumnValue(row, column);
		}
		catch (RuntimeException e)
		{
			return "<error: " + e.getClass().getSimpleName() + ">";
		}
	}
}
