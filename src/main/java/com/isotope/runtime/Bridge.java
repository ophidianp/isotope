package com.isotope.runtime;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only Isotope code that rewritten mods call at runtime.
 *
 * <p>Some game APIs went from "an object that knows its type, and the type knows its codec"
 * to "an object that knows its codec" (26.2's structure processors, for example). Old mods
 * still build and register the type objects. The rewriter adds a {@code codec()} method to
 * their classes that asks {@link #codecOf} for the codec, and sends their
 * {@code Registry.register} calls through {@link #register}, which registers the codec
 * instead of the type object wherever the registry now holds codecs.
 *
 * <p>Everything is done by reflection, so this class names no game class and runs on every
 * 26.x version. It does nothing at all for objects it does not recognise.
 */
public final class Bridge {
	private static final Map<Class<?>, Method> CODEC_METHODS = new ConcurrentHashMap<>();
	private static final Map<String, Method> REGISTER_METHODS = new ConcurrentHashMap<>();
	private static final Method NONE;

	static {
		try {
			NONE = Object.class.getMethod("toString");
		} catch (NoSuchMethodException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private Bridge() { }

	/** The codec a type object hands out, or the object itself if it already is a codec. */
	public static Object codecOf(Object typeObject) {
		if (typeObject == null || isMapCodec(typeObject)) return typeObject;
		Method m = codecMethod(typeObject.getClass());
		if (m == NONE) throw new IllegalStateException("Isotope: " + typeObject.getClass().getName() + " has no codec() to bridge to");

		try {
			return m.invoke(typeObject);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Isotope: could not get the codec from " + typeObject.getClass().getName(), e);
		}
	}

	/**
	 * Stands in for {@code Registry.register(registry, id, value)} in rewritten mods. Returns
	 * the value the mod passed in, exactly like the original.
	 */
	public static Object register(Object registry, Object id, Object value) {
		Object toRegister = value;

		if (value != null && !isMapCodec(value) && codecMethod(value.getClass()) != NONE && holdsCodecs(registry)) {
			toRegister = codecOf(value);
		}

		Method register = registerMethod(registry, id);

		try {
			register.invoke(null, registry, id, toRegister);
		} catch (ReflectiveOperationException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof RuntimeException re) throw re;
			if (cause instanceof Error err) throw err;
			throw new IllegalStateException(cause);
		}

		return value;
	}

	private static boolean isMapCodec(Object o) {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().equals("com.mojang.serialization.MapCodec")) return true;
		}

		return false;
	}

	/** True if the registry's existing entries are codecs (vanilla registers its own before mods run). */
	private static boolean holdsCodecs(Object registry) {
		if (!(registry instanceof Iterable<?> it)) return false;
		Iterator<?> i = it.iterator();
		return i.hasNext() && isMapCodec(i.next());
	}

	private static Method codecMethod(Class<?> type) {
		return CODEC_METHODS.computeIfAbsent(type, t -> {
			for (Method m : t.getMethods()) {
				if (m.getName().equals("codec") && m.getParameterCount() == 0 && !m.getReturnType().isPrimitive()) {
					m.setAccessible(true);
					return m;
				}
			}

			return NONE;
		});
	}

	/** The real static Registry.register(registry, id, value) whose id parameter fits this id. */
	private static Method registerMethod(Object registry, Object id) {
		return REGISTER_METHODS.computeIfAbsent(id.getClass().getName(), k -> {
			try {
				Class<?> registryType = Class.forName("net.minecraft.core.Registry", false, Bridge.class.getClassLoader());

				for (Method m : registryType.getMethods()) {
					Class<?>[] p = m.getParameterTypes();
					if (m.getName().equals("register") && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && p.length == 3
							&& p[0] == registryType && p[1].isInstance(id) && p[2] == Object.class) return m;
				}
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}

			throw new IllegalStateException("Isotope: no Registry.register for " + id.getClass().getName());
		});
	}
}
