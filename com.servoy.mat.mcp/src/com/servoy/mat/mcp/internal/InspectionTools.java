/*
 * Copyright (C) 2026 Servoy BV
 *
 * Licensed under the Eclipse Public License 2.0 (EPL-2.0).
 */
package com.servoy.mat.mcp.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.mat.snapshot.Histogram;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.ClassHistogramRecord;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.util.VoidProgressListener;

/**
 * Implements the heap-analysis MCP tools on top of the MAT {@link ISnapshot} API. Each method takes
 * the resolved active snapshot and the parsed arguments, and returns a JSON string. Keeping the JSON
 * rendering here (not in {@link MatMcpTools}) keeps the tool wiring thin.
 *
 * <p>These surface the operations MAT itself exposes: histogram, dominator tree, retained set,
 * paths to GC roots, inbound/outbound references, object inspection (fields + values), class
 * details, GC-root listing, and thread stacks with their frame locals.</p>
 */
public final class InspectionTools
{
	private InspectionTools()
	{
	}

	// ---- histogram ------------------------------------------------------------------------

	/** Top classes by object count / shallow size, optionally filtered by a class-name regex. */
	public static String histogram(ISnapshot snapshot, String classNamePattern, int limit) throws Exception
	{
		Histogram histogram = snapshot.getHistogram(new VoidProgressListener());
		Collection<ClassHistogramRecord> records = histogram.getClassHistogramRecords();

		Pattern pattern = (classNamePattern != null && !classNamePattern.isBlank()) ? Pattern.compile(classNamePattern) : null;

		ClassHistogramRecord[] arr = records.toArray(new ClassHistogramRecord[0]);
		// sort by shallow size desc
		java.util.Arrays.sort(arr, (a, b) -> Long.compare(b.getUsedHeapSize(), a.getUsedHeapSize()));

		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"rows\":[");
		int shown = 0;
		for (ClassHistogramRecord r : arr)
		{
			if (shown >= limit) break;
			if (pattern != null && !pattern.matcher(r.getLabel()).find()) continue;
			if (shown > 0) sb.append(',');
			sb.append('{');
			sb.append("\"class\":").append(Json.escape(r.getLabel()));
			sb.append(",\"objects\":").append(r.getNumberOfObjects());
			sb.append(",\"shallowSize\":").append(r.getUsedHeapSize());
			sb.append('}');
			shown++;
			// (getNumberOfObjects()/getUsedHeapSize() are long on HistogramRecord)
		}
		sb.append("],\"shown\":").append(shown);
		sb.append(",\"totalClasses\":").append(records.size());
		sb.append('}');
		return sb.toString();
	}

	// ---- read_object ----------------------------------------------------------------------

	/** Full detail of one object: identity, sizes, GC-root status, fields with resolved values, outbound refs. */
	public static String readObject(ISnapshot snapshot, int objectId, int maxRefs) throws Exception
	{
		IObject o = snapshot.getObject(objectId);
		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		sb.append("\"objectId\":").append(objectId);
		sb.append(",\"address\":").append(Json.escape(ObjectJson.toHex(o.getObjectAddress())));
		sb.append(",\"class\":").append(Json.escape(o.getClazz() != null ? o.getClazz().getName() : "?"));
		sb.append(",\"displayName\":").append(Json.escape(o.getDisplayName()));
		sb.append(",\"shallowSize\":").append(o.getUsedHeapSize());
		sb.append(",\"retainedSize\":").append(o.getRetainedHeapSize());
		sb.append(",\"isGCRoot\":").append(snapshot.isGCRoot(objectId));
		sb.append(",\"isClass\":").append(snapshot.isClass(objectId));
		sb.append(",\"isClassLoader\":").append(snapshot.isClassLoader(objectId));
		sb.append(",\"isArray\":").append(snapshot.isArray(objectId));

		// instance fields with resolved values: primitives inline, reference fields as {objectId,address}
		if (o instanceof IInstance instance)
		{
			List<Field> fields = instance.getFields();
			sb.append(",\"fields\":[");
			boolean first = true;
			for (Field f : fields)
			{
				if (!first) sb.append(',');
				first = false;
				sb.append('{');
				sb.append("\"name\":").append(Json.escape(f.getName()));
				sb.append(",\"type\":").append(Json.escape(f.getVerboseSignature()));
				appendFieldValue(sb, snapshot, f.getValue());
				sb.append('}');
			}
			sb.append(']');
		}
		else if (o instanceof IClass clazz)
		{
			// a Class object: expose its static fields the same way
			List<Field> statics = clazz.getStaticFields();
			sb.append(",\"staticFields\":[");
			boolean first = true;
			for (Field f : statics)
			{
				if (!first) sb.append(',');
				first = false;
				sb.append('{');
				sb.append("\"name\":").append(Json.escape(f.getName()));
				sb.append(",\"type\":").append(Json.escape(f.getVerboseSignature()));
				appendFieldValue(sb, snapshot, f.getValue());
				sb.append('}');
			}
			sb.append(']');
		}

		// outbound references (named): name -> target descriptor
		List<NamedReference> refs = o.getOutboundReferences();
		int shownRefs = Math.min(refs.size(), maxRefs);
		sb.append(",\"outboundReferenceCount\":").append(refs.size());
		sb.append(",\"outboundReferences\":[");
		for (int i = 0; i < shownRefs; i++)
		{
			NamedReference ref = refs.get(i);
			if (i > 0) sb.append(',');
			sb.append('{');
			sb.append("\"name\":").append(Json.escape(ref.getName()));
			sb.append(",\"address\":").append(Json.escape(ObjectJson.toHex(ref.getObjectAddress())));
			try
			{
				sb.append(",\"objectId\":").append(snapshot.mapAddressToId(ref.getObjectAddress()));
			}
			catch (Exception ignore)
			{
				// reference may point outside the heap; omit the id
			}
			sb.append('}');
		}
		sb.append(']');
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Renders a MAT field value as JSON. Reference fields come back as an {@link ObjectReference};
	 * we resolve those to an {@code objectId}+{@code address} so they can be navigated. Primitive
	 * fields are the boxed value and are written inline; null is null.
	 */
	private static void appendFieldValue(StringBuilder sb, ISnapshot snapshot, Object value)
	{
		if (value == null)
		{
			sb.append(",\"value\":null");
			return;
		}
		if (value instanceof ObjectReference ref)
		{
			sb.append(",\"refAddress\":").append(Json.escape(ObjectJson.toHex(ref.getObjectAddress())));
			try
			{
				sb.append(",\"refObjectId\":").append(ref.getObjectId());
			}
			catch (Exception ignore)
			{
				// reference target not in the heap; address only
			}
			return;
		}
		// primitive / String-ish boxed value
		sb.append(",\"value\":").append(Json.value(value));
	}

	// ---- array_elements -------------------------------------------------------------------

	/**
	 * The elements of an array object. For a primitive array the values are written inline; for an
	 * object (reference) array each element is an object descriptor. A window [from, from+limit) keeps
	 * the response bounded on huge arrays.
	 */
	public static String arrayElements(ISnapshot snapshot, int objectId, int from, int limit) throws Exception
	{
		IObject o = snapshot.getObject(objectId);
		if (!(o instanceof org.eclipse.mat.snapshot.model.IArray array))
		{
			return "{\"objectId\":" + objectId + ",\"error\":\"object is not an array\"}";
		}
		int length = array.getLength();
		int start = Math.max(0, from);
		int count = Math.max(0, Math.min(limit, length - start));

		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		sb.append("\"objectId\":").append(objectId);
		sb.append(",\"class\":").append(Json.escape(o.getClazz() != null ? o.getClazz().getName() : "?"));
		sb.append(",\"length\":").append(length);
		sb.append(",\"from\":").append(start);
		sb.append(",\"returned\":").append(count);

		if (array instanceof IPrimitiveArray prim)
		{
			sb.append(",\"primitive\":true");
			sb.append(",\"componentType\":").append(Json.escape(prim.getComponentType().getName()));
			sb.append(",\"elements\":[");
			for (int i = 0; i < count; i++)
			{
				if (i > 0) sb.append(',');
				sb.append(Json.value(prim.getValueAt(start + i)));
			}
			sb.append(']');
		}
		else if (array instanceof IObjectArray objArr)
		{
			sb.append(",\"primitive\":false");
			long[] addresses = objArr.getReferenceArray(start, count);
			sb.append(",\"elements\":[");
			for (int i = 0; i < addresses.length; i++)
			{
				if (i > 0) sb.append(',');
				long addr = addresses[i];
				if (addr == 0)
				{
					sb.append("null");
					continue;
				}
				sb.append('{');
				sb.append("\"address\":").append(Json.escape(ObjectJson.toHex(addr)));
				try
				{
					sb.append(",\"objectId\":").append(snapshot.mapAddressToId(addr));
				}
				catch (Exception ignore)
				{
					// element target not in the heap
				}
				sb.append('}');
			}
			sb.append(']');
		}
		sb.append('}');
		return sb.toString();
	}

	// ---- outbound / inbound references ----------------------------------------------------

	public static String outboundReferences(ISnapshot snapshot, int objectId, int limit) throws Exception
	{
		int[] ids = snapshot.getOutboundReferentIds(objectId);
		return refsJson(snapshot, "outbound", objectId, ids, limit);
	}

	public static String inboundReferences(ISnapshot snapshot, int objectId, int limit) throws Exception
	{
		int[] ids = snapshot.getInboundRefererIds(objectId);
		return refsJson(snapshot, "inbound", objectId, ids, limit);
	}

	private static String refsJson(ISnapshot snapshot, String direction, int objectId, int[] ids, int limit)
	{
		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		sb.append("\"direction\":").append(Json.escape(direction));
		sb.append(",\"objectId\":").append(objectId);
		sb.append(",\"count\":").append(ids.length);
		sb.append(",\"truncated\":").append(ids.length > limit);
		sb.append(",\"references\":");
		ObjectJson.appendObjectArray(sb, snapshot, ids, limit);
		sb.append('}');
		return sb.toString();
	}

	// ---- paths to GC roots ----------------------------------------------------------------

	/** The shortest path from the object to a GC root - why the object is kept alive. */
	public static String pathToGcRoots(ISnapshot snapshot, int objectId) throws Exception
	{
		int[] path = snapshot.getPathsFromGCRoots(objectId, Map.of()).getNextShortestPath();
		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		sb.append("\"objectId\":").append(objectId);
		if (path == null)
		{
			sb.append(",\"path\":null,\"note\":\"no path to a GC root found\"}");
			return sb.toString();
		}
		sb.append(",\"pathLength\":").append(path.length);
		// path[0] is the object itself, last element is the GC root
		sb.append(",\"path\":");
		ObjectJson.appendObjectArray(sb, snapshot, path, path.length);
		sb.append('}');
		return sb.toString();
	}

	// ---- retained set ---------------------------------------------------------------------

	/** Total retained size of (the objects kept alive only by) the given objects. */
	public static String retainedSet(ISnapshot snapshot, int[] objectIds) throws Exception
	{
		int[] retained = snapshot.getRetainedSet(objectIds, new VoidProgressListener());
		long size = snapshot.getHeapSize(retained);
		StringBuilder sb = new StringBuilder(128);
		sb.append('{');
		sb.append("\"inputObjectCount\":").append(objectIds.length);
		sb.append(",\"retainedObjectCount\":").append(retained.length);
		sb.append(",\"retainedSize\":").append(size);
		sb.append('}');
		return sb.toString();
	}

	// ---- dominator tree (top retainers by class) ------------------------------------------

	/**
	 * The classes retaining the most heap - MAT's "biggest top-level dominator classes" (the Top
	 * Consumers view). The objects directly under the dominator-tree root (id -1) are the heap's
	 * top-level retainers; we aggregate their retained heap per class, matching how MAT's
	 * TopConsumers query seeds and sums.
	 */
	public static String topDominatorClasses(ISnapshot snapshot, int limit) throws Exception
	{
		int[] topDominators = snapshot.getImmediateDominatedIds(-1);

		// aggregate retained heap + count per class of the top-level dominators
		Map<String, long[]> byClass = new java.util.HashMap<>(); // class -> {count, retained}
		for (int id : topDominators)
		{
			long retained = snapshot.getRetainedHeapSize(id);
			String className;
			try
			{
				IClass c = snapshot.getClassOf(id);
				className = c != null ? c.getName() : "<unknown>";
			}
			catch (Exception e)
			{
				className = "<unknown>";
			}
			long[] agg = byClass.computeIfAbsent(className, k -> new long[2]);
			agg[0]++;
			agg[1] += retained;
		}

		List<Map.Entry<String, long[]>> rows = new java.util.ArrayList<>(byClass.entrySet());
		rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));

		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"rows\":[");
		int shown = Math.min(rows.size(), limit);
		for (int i = 0; i < shown; i++)
		{
			Map.Entry<String, long[]> e = rows.get(i);
			if (i > 0) sb.append(',');
			sb.append('{');
			sb.append("\"class\":").append(Json.escape(e.getKey()));
			sb.append(",\"topLevelDominatorCount\":").append(e.getValue()[0]);
			sb.append(",\"retainedSize\":").append(e.getValue()[1]);
			sb.append('}');
		}
		sb.append("],\"shown\":").append(shown);
		sb.append(",\"totalTopLevelDominators\":").append(topDominators.length);
		sb.append(",\"distinctClasses\":").append(rows.size());
		sb.append('}');
		return sb.toString();
	}

	// ---- class details --------------------------------------------------------------------

	/** Classes matching a name (exact or regex): object counts, sizes, hierarchy, static fields. */
	public static String classDetails(ISnapshot snapshot, String name, boolean regex, int limit) throws Exception
	{
		Collection<IClass> classes = regex
			? snapshot.getClassesByName(Pattern.compile(name), false)
			: snapshot.getClassesByName(name, false);

		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"matchedClasses\":").append(classes == null ? 0 : classes.size());
		sb.append(",\"classes\":[");
		int shown = 0;
		if (classes != null)
		{
			for (IClass c : classes)
			{
				if (shown >= limit) break;
				if (shown > 0) sb.append(',');
				sb.append('{');
				sb.append("\"name\":").append(Json.escape(c.getName()));
				sb.append(",\"objectId\":").append(c.getObjectId());
				sb.append(",\"numberOfObjects\":").append(c.getNumberOfObjects());
				sb.append(",\"heapSizePerInstance\":").append(c.getHeapSizePerInstance());
				sb.append(",\"superClass\":").append(Json.escape(c.getSuperClass() != null ? c.getSuperClass().getName() : null));
				sb.append(",\"isArrayType\":").append(c.isArrayType());
				sb.append('}');
				shown++;
			}
		}
		sb.append("]}");
		return sb.toString();
	}

	// ---- GC roots -------------------------------------------------------------------------

	public static String gcRoots(ISnapshot snapshot, int limit) throws Exception
	{
		int[] roots = snapshot.getGCRoots();
		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		sb.append("\"count\":").append(roots.length);
		sb.append(",\"truncated\":").append(roots.length > limit);
		sb.append(",\"roots\":[");
		int shown = Math.min(roots.length, limit);
		for (int i = 0; i < shown; i++)
		{
			if (i > 0) sb.append(',');
			int id = roots[i];
			sb.append('{');
			sb.append("\"objectId\":").append(id);
			try
			{
				IObject o = snapshot.getObject(id);
				sb.append(",\"class\":").append(Json.escape(o.getClazz() != null ? o.getClazz().getName() : "?"));
				GCRootInfo[] info = snapshot.getGCRootInfo(id);
				sb.append(",\"rootType\":").append(Json.escape(info != null ? GCRootInfo.getTypeSetAsString(info) : null));
			}
			catch (Exception ignore)
			{
				// keep the id even if details fail
			}
			sb.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	// ---- thread stacks (with frame locals) ------------------------------------------------

	/**
	 * All threads with their stack frames and the local objects on each frame. This is the capability
	 * OQL cannot provide and was the original SVY-21574 blocker: it exposes the actual objects (and
	 * thus arguments) held on a frame, e.g. the index/array a method is working on.
	 */
	public static String threadStacks(ISnapshot snapshot, int maxLocalsPerFrame) throws Exception
	{
		// threads are instances of java.lang.Thread
		Collection<IClass> threadClasses = snapshot.getClassesByName("java.lang.Thread", true); //$NON-NLS-1$
		StringBuilder sb = new StringBuilder(1024);
		sb.append("{\"threads\":[");
		boolean firstThread = true;
		if (threadClasses != null)
		{
			for (IClass tc : threadClasses)
			{
				for (int threadId : tc.getObjectIds())
				{
					IThreadStack stack = snapshot.getThreadStack(threadId);
					if (stack == null) continue;
					if (!firstThread) sb.append(',');
					firstThread = false;
					sb.append('{');
					IObject threadObj = snapshot.getObject(threadId);
					sb.append("\"threadObjectId\":").append(threadId);
					sb.append(",\"thread\":").append(Json.escape(threadObj.getDisplayName()));
					sb.append(",\"frames\":[");
					IStackFrame[] frames = stack.getStackFrames();
					for (int f = 0; f < frames.length; f++)
					{
						if (f > 0) sb.append(',');
						IStackFrame frame = frames[f];
						sb.append('{');
						sb.append("\"frame\":").append(Json.escape(frame.getText()));
						int[] locals = frame.getLocalObjectsIds();
						sb.append(",\"localCount\":").append(locals != null ? locals.length : 0);
						sb.append(",\"locals\":");
						ObjectJson.appendObjectArray(sb, snapshot, locals != null ? locals : new int[0], maxLocalsPerFrame);
						sb.append('}');
					}
					sb.append("]}");
				}
			}
		}
		sb.append("]}");
		return sb.toString();
	}
}
