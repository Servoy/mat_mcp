/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

/**
 * Tiny JSON string builder helper. The MCP SDK handles the protocol-level JSON; these helpers only
 * build the textual JSON payloads that our tools return as their text content, so we avoid pulling
 * extra JSON libraries into the plugin's class path.
 */
public final class Json
{
	private Json()
	{
	}

	public static String escape(String s)
	{
		if (s == null) return "null";
		StringBuilder out = new StringBuilder(s.length() + 8);
		out.append('"');
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
					if (c < 0x20)
					{
						out.append(String.format("\\u%04x", (int)c));
					}
					else
					{
						out.append(c);
					}
			}
		}
		out.append('"');
		return out.toString();
	}

	/**
	 * Renders an arbitrary cell value as a JSON value: numbers as numbers, booleans as booleans,
	 * null as null, everything else as a JSON string via its toString().
	 */
	public static String value(Object v)
	{
		if (v == null) return "null";
		if (v instanceof Number || v instanceof Boolean) return v.toString();
		return escape(v.toString());
	}
}
