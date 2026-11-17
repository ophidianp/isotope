package com.isotope.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a mod's data-pack JSON (loot tables, advancements, predicates, ...) from one
 * game version's format to the next.
 *
 * <p>Data files are not code, so the bytecode rewrite never sees them, but the game reads
 * them at world load and a single file in the old format stops the server ("Failed to load
 * registries"). Each rule here was found by comparing Mojang's own vanilla data between the
 * two versions, and the whole converter is checked by {@code com.isotope.tools.DataCheck},
 * which converts every vanilla file of the old version and compares the result with the
 * new version's file.
 *
 * <p>Only one step has rules so far: 26.2 to 26.3, where loot conditions and functions
 * changed shape. Steps without rules leave files untouched.
 */
public final class DataMigrator {
	private DataMigrator() { }

	/** True if this step has data rules. */
	public static boolean supports(String from, String to) {
		return from.equals("26.2") && to.equals("26.3");
	}

	/**
	 * Converts one parsed JSON file in place-ish (returns the converted tree).
	 *
	 * @param path the file's path inside the jar, e.g. {@code data/mymod/loot_table/blocks/x.json};
	 *             used to decide which rules apply
	 */
	@SuppressWarnings("unchecked")
	public static Object migrate(String path, Object json, String from, String to) {
		if (!supports(from, to)) return json;
		String folder = folder(path);
		if (folder == null) return json;
		boolean worldgen = folder.startsWith("worldgen/") || folder.equals("enchantment");
		Object out = convert(json, null, worldgen);
		if (folder.equals("worldgen/biome") && out instanceof Map<?, ?> biome) out = biome((Map<String, Object>) biome);
		return out;
	}

	/** "loot_table", "advancement", "worldgen/processor_list", ... or null if not a data file. */
	static String folder(String path) {
		if (!path.startsWith("data/") || !path.endsWith(".json")) return null;
		String[] p = path.split("/");
		if (p.length < 4) return null;
		return p[2].equals("worldgen") && p.length >= 5 ? "worldgen/" + p[3] : p[2];
	}

	/** Loot condition objects identify themselves with a "condition" key in the old format. */
	private static boolean isOldCondition(Object o) {
		return o instanceof Map<?, ?> m && m.get("condition") instanceof String && !m.containsKey("type");
	}

	private static boolean isOldFunction(Object o) {
		return o instanceof Map<?, ?> m && m.get("function") instanceof String && !m.containsKey("type");
	}

	private static boolean allOldConditions(List<?> list) {
		if (list.isEmpty()) return false;
		for (Object o : list) if (!isOldCondition(o)) return false;
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Object convert(Object node, String key, boolean worldgen) {
		if (node instanceof List<?> list) {
			List<Object> out = new ArrayList<>();
			for (Object o : list) out.add(convert(o, key, worldgen));
			return out;
		}

		if (!(node instanceof Map<?, ?>)) return node;
		Map<String, Object> in = (Map<String, Object>) node;
		Map<String, Object> out = new LinkedHashMap<>();
		boolean recipeUnlocked = "minecraft:recipe_unlocked".equals(in.get("trigger"));

		for (Map.Entry<String, Object> e : in.entrySet()) {
			String k = e.getKey();
			Object v = e.getValue();

			// A list of loot conditions became one condition: the single one, or an all_of.
			// "terms" (inside any_of / all_of) stays a list.
			if (v instanceof List<?> list && allOldConditions(list) && !k.equals("terms")) {
				Object single = collapse(list, worldgen);
				out.put(k.equals("conditions") ? "condition" : k, single);
				continue;
			}

			// An empty "conditions" list means "no condition"; the new format just leaves it out.
			if (k.equals("conditions") && v instanceof List<?> list && list.isEmpty()) continue;

			// "functions": [ {function: ...} ] became "modifier": [ {type: ...} ].
			if (k.equals("functions") && v instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(DataMigrator::isOldFunction)) {
				// One function is written as a plain object, several as a list.
				Object converted = convert(v, k, worldgen);
				out.put("modifier", list.size() == 1 ? ((List<?>) converted).get(0) : converted);
				continue;
			}

			// Advancement trigger conditions.
			if (k.equals("conditions") && v instanceof Map<?, ?> cond) {
				Map<String, Object> c = (Map<String, Object>) convert(cond, k, worldgen);
				if (recipeUnlocked && c.containsKey("recipe") && !c.containsKey("recipes")) c = renameKey(c, "recipe", "recipes");
				if (c.containsKey("loot_table") && !c.containsKey("loot_tables")) c = renameKey(c, "loot_table", "loot_tables");
				if (c.get("block") instanceof String && !c.containsKey("blocks")) c = renameKey(c, "block", "blocks");
				out.put(k, c);
				continue;
			}

			out.put(k, convert(v, k, worldgen));
		}

		// The discriminators themselves.
		if (out.get("condition") instanceof String c && !out.containsKey("type")) {
			out = renameKey(out, "condition", "type");

			if (c.equals("minecraft:block_state_property")) {
				// Renamed, and its fields with it: {block, properties} -> {blocks, state}.
				out.put("type", "minecraft:match_block");
				out = renameKey(out, "block", "blocks");
				out = renameKey(out, "properties", "state");
			}
		} else if (out.get("function") instanceof String && !out.containsKey("type")) {
			out = renameKey(out, "function", "type");
		}

		// Damage-type tag checks: {"id": "minecraft:is_fall", "expected": ...} now names the tag with "#".
		if (out.get("tags") instanceof List<?> tags) {
			List<Object> fixed = new ArrayList<>();

			for (Object t : tags) {
				if (t instanceof Map<?, ?> tm && tm.get("id") instanceof String id && tm.containsKey("expected") && !id.startsWith("#")) {
					Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) tm);
					copy.put("id", "#" + id);
					fixed.add(copy);
				} else {
					fixed.add(t);
				}
			}

			out.put("tags", fixed);
		}

		// Number providers: sum{summands} -> add{inputs}.
		if ("minecraft:sum".equals(out.get("type")) && out.containsKey("summands")) {
			out.put("type", "minecraft:add");
			out = renameKey(out, "summands", "inputs");
		}

		if (worldgen) {
			// Mob spawn entries: minCount/maxCount -> count (a number, or a uniform range).
			if (out.get("type") instanceof String && out.get("minCount") instanceof Number min && out.get("maxCount") instanceof Number max) {
				Map<String, Object> entry = new LinkedHashMap<>();

				for (Map.Entry<String, Object> e : out.entrySet()) {
					if (e.getKey().equals("minCount")) {
						if (min.longValue() == max.longValue()) {
							entry.put("count", min);
						} else {
							Map<String, Object> range = new LinkedHashMap<>();
							range.put("type", "minecraft:uniform");
							range.put("max_inclusive", max);
							range.put("min_inclusive", min);
							entry.put("count", range);
						}
					} else if (!e.getKey().equals("maxCount")) {
						entry.put(e.getKey(), e.getValue());
					}
				}

				out = entry;
			}

			// Block states: {"Name", "Properties"} -> {"id", "properties"}.
			if (out.get("Name") instanceof String n && n.indexOf(':') > 0 && !out.containsKey("id")) {
				out = renameKey(out, "Name", "id");
				out = renameKey(out, "Properties", "properties");
			}

			// Block state providers lost their "_state_provider" suffix.
			if (out.get("type") instanceof String t && t.startsWith("minecraft:") && t.endsWith("_state_provider")) {
				out.put("type", t.substring(0, t.length() - "_state_provider".length()));
			}
		}

		return out;
	}

	/**
	 * Biomes: spawn lists and costs moved into the "natural_mob_spawns" environment attribute
	 * (empty categories are left out), and creature_spawn_probability became an attribute too.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> biome(Map<String, Object> in) {
		if (!in.containsKey("spawners") && !in.containsKey("creature_spawn_probability")) return in;
		Map<String, Object> out = new LinkedHashMap<>(in);
		Map<String, Object> attributes = out.get("attributes") instanceof Map<?, ?> a ? new LinkedHashMap<>((Map<String, Object>) a) : new LinkedHashMap<>();

		if (out.get("spawners") instanceof Map<?, ?> spawners) {
			Map<String, Object> byCategory = new LinkedHashMap<>();

			for (Map.Entry<String, Object> e : ((Map<String, Object>) spawners).entrySet()) {
				if (!(e.getValue() instanceof List<?> l) || !l.isEmpty()) byCategory.put(e.getKey(), e.getValue());
			}

			Map<String, Object> argument = new LinkedHashMap<>();
			argument.put("spawn_costs", out.get("spawn_costs") instanceof Map<?, ?> c ? c : new LinkedHashMap<>());
			argument.put("spawns_by_category", byCategory);
			Map<String, Object> attribute = new LinkedHashMap<>();
			attribute.put("argument", argument);
			attribute.put("modifier", "overlay"); // what all 67 vanilla 26.3 biomes use
			attributes.put("minecraft:gameplay/natural_mob_spawns", attribute);
			out.remove("spawners");
			out.remove("spawn_costs");
		}

		if (out.containsKey("creature_spawn_probability")) {
			attributes.put("minecraft:gameplay/creature_world_gen_spawn_probability", out.remove("creature_spawn_probability"));
		}

		out.put("attributes", attributes);
		return out;
	}

	private static Object collapse(List<?> conditions, boolean worldgen) {
		List<Object> converted = new ArrayList<>();
		for (Object c : conditions) converted.add(convert(c, null, worldgen));
		if (converted.size() == 1) return converted.get(0);
		Map<String, Object> all = new LinkedHashMap<>();
		all.put("type", "minecraft:all_of");
		all.put("terms", converted);
		return all;
	}

	/** Renames a key while keeping its position (so converted files diff cleanly). */
	private static Map<String, Object> renameKey(Map<String, Object> m, String from, String to) {
		if (!m.containsKey(from)) return m;
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
		return out;
	}
}
