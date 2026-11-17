package com.isotope.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.isotope.model.ClassInfo;

/**
 * The classes that will really exist when the game runs: the mod being checked, the
 * running Minecraft jar, every other installed mod, the libraries and the JDK.
 *
 * <p>This is the ground truth the pre-flight check links against. Redirect tables say
 * what Isotope <i>believes</i> changed; this says what is actually there.
 */
public final class LiveIndex {
	/** Looks a class up by internal name; returns its bytes or null. */
	public interface Source {
		byte[] find(String internalName);

		/** True if this source has any class in the package ("dev/example/lib/"). Optional. */
		default boolean hasPackage(String packagePath) {
			return false;
		}
	}

	/**
	 * True if some installed jar has classes in this package. Tells "the library is not installed"
	 * (an optional integration that stays dormant) from "the library is installed, but in a version
	 * without this class" (a real failure).
	 */
	public boolean hasPackage(String packagePath) {
		for (Source s : sources) {
			try {
				if (s.hasPackage(packagePath)) return true;
			} catch (RuntimeException e) {
				// ignore this source
			}
		}

		return false;
	}

	private static final ClassInfo MISSING = new ClassInfo("", null, null, 0, Map.of(), Map.of());

	private final List<Source> sources = new ArrayList<>();
	private final Map<String, ClassInfo> cache = new HashMap<>();

	public LiveIndex(List<Source> sources) {
		this.sources.addAll(sources);
	}

	/** A copy that looks in {@code first} before everything else (used for the mod's own classes). */
	public LiveIndex withFirst(Source first) {
		List<Source> list = new ArrayList<>();
		list.add(first);
		list.addAll(sources);
		return new LiveIndex(list);
	}

	/** The class, or null if it exists nowhere. */
	public ClassInfo get(String internalName) {
		if (internalName == null) return null;
		ClassInfo info = cache.get(internalName);

		if (info == null) {
			info = MISSING;

			for (Source s : sources) {
				byte[] bytes;

				try {
					bytes = s.find(internalName);
				} catch (RuntimeException e) {
					bytes = null;
				}

				if (bytes != null) {
					try {
						info = ClassInfo.read(bytes);
					} catch (RuntimeException e) {
						info = MISSING; // unreadable class file: treat as absent
					}

					break;
				}
			}

			cache.put(internalName, info);
		}

		return info == MISSING ? null : info;
	}
}
