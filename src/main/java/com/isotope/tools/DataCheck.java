package com.isotope.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.isotope.rewrite.DataMigrator;
import com.isotope.util.Json;

/**
 * Proves the data migrator against Mojang's own data: converts every vanilla JSON file of
 * the old version and compares the result with the same file in the new version.
 *
 * <pre>DataCheck &lt;from&gt; &lt;to&gt; &lt;dir with old data/&gt; &lt;dir with new data/&gt; [folder filter]</pre>
 * Prints, per data folder, how many files now match exactly (numbers compared by value),
 * and the first difference in a few of the files that do not.
 */
public final class DataCheck {
	public static void main(String[] args) throws IOException {
		Path oldRoot = Paths.get(args[2]);
		Path newRoot = Paths.get(args[3]);
		String filter = args.length > 4 ? args[4] : "";
		predicates = newRoot.resolve("data/minecraft/predicate");
		Map<String, int[]> stats = new TreeMap<>();
		Map<String, Integer> shown = new TreeMap<>();
		Map<String, Integer> kinds = new TreeMap<>();

		try (Stream<Path> walk = Files.walk(oldRoot.resolve("data"))) {
			for (Path p : walk.filter(x -> x.toString().endsWith(".json")).toList()) {
				String rel = oldRoot.relativize(p).toString().replace('\\', '/');
				Path q = newRoot.resolve(rel);
				if (!Files.exists(q) || !rel.contains(filter)) continue;
				String[] parts = rel.split("/");
				String folder = parts[2].equals("worldgen") ? "worldgen/" + parts[3] : parts[2];
				Object before = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
				Object expected = Json.parse(Files.readString(q, StandardCharsets.UTF_8));
				Object after = DataMigrator.migrate(rel, before, args[0], args[1]);
				int[] s = stats.computeIfAbsent(folder, k -> new int[3]);
				s[0]++;
				if (same(before, expected)) s[2]++; // unchanged between versions
				String diff = firstDiff(after, expected, "");

				if (diff == null) {
					s[1]++;
				} else {
					// Group differences by kind: the path without list indices, plus what kind of difference.
					String kind = folder + "  " + diff.replaceAll("\\[\\d+\\]", "[]").replaceAll(": [^:]*( vs .*)$", ": <value>$1").replaceAll(" vs .*", " vs ...");
					if (kind.length() > 160) kind = kind.substring(0, 160);
					kinds.merge(kind, 1, Integer::sum);
					if (shown.merge(folder, 1, Integer::sum) <= 2) System.out.println("  " + rel + ": " + diff);
				}
			}
		}

		if (System.getProperty("kinds") != null) {
			System.out.println("\nmost common differences:");
			kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(Integer.getInteger("kinds", 15))
					.forEach(e -> System.out.printf("%6d  %s%n", e.getValue(), e.getKey()));
		}

		System.out.println();
		System.out.printf("%-34s %6s %8s %10s%n", "folder", "files", "matched", "unchanged");

		for (Map.Entry<String, int[]> e : stats.entrySet()) {
			int[] s = e.getValue();
			System.out.printf("%-34s %6d %7.1f%% %9.1f%%%n", e.getKey(), s[0], 100.0 * s[1] / s[0], 100.0 * s[2] / s[0]);
		}
	}

	static boolean same(Object a, Object b) {
		return firstDiff(a, b, "") == null;
	}

	/** Where the new version's named predicates live, so "minecraft:tool/can_shear" can be compared by content. */
	static Path predicates;

	/** Path and values of the first difference, or null if equal. Numbers compare by value. */
	static String firstDiff(Object a, Object b, String at) {
		// The new version may name a predicate ("minecraft:tool/can_shear") where the converted
		// file has the same condition written out. Compare against the named file's content.
		if (a instanceof Map<?, ?> && b instanceof String ref && predicates != null && ref.startsWith("minecraft:")) {
			Path p = predicates.resolve(ref.substring("minecraft:".length()) + ".json");

			if (Files.exists(p)) {
				try {
					return firstDiff(a, Json.parse(Files.readString(p, StandardCharsets.UTF_8)), at + "(" + ref + ")");
				} catch (IOException e) {
					return at + ": could not read " + p;
				}
			}
		}

		if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue() ? null : at + ": " + a + " vs " + b;

		if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
			for (Object k : x.keySet()) if (!y.containsKey(k)) return at + "/" + k + ": only in converted";
			for (Object k : y.keySet()) if (!x.containsKey(k)) return at + "/" + k + ": only in vanilla 26.3";

			for (Object k : x.keySet()) {
				String d = firstDiff(x.get(k), y.get(k), at + "/" + k);
				if (d != null) return d;
			}

			return null;
		}

		if (a instanceof List<?> x && b instanceof List<?> y) {
			if (x.size() != y.size()) return at + ": list of " + x.size() + " vs " + y.size();

			for (int i = 0; i < x.size(); i++) {
				String d = firstDiff(x.get(i), y.get(i), at + "[" + i + "]");
				if (d != null) return d;
			}

			return null;
		}

		if (a == null ? b == null : a.equals(b)) return null;
		String sa = String.valueOf(a), sb = String.valueOf(b);
		return at + ": " + (sa.length() > 70 ? sa.substring(0, 70) + "..." : sa) + " vs " + (sb.length() > 70 ? sb.substring(0, 70) + "..." : sb);
	}
}
