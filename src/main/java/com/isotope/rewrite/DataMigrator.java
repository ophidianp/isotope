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
		return (from.equals("26.2") && to.equals("26.3")) || (from.equals("26.3") && to.equals("26.2"));
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
		if (from.equals("26.3")) return Back.migrate(folder, json, worldgen);
		if (folder.equals("loot_table") || folder.equals("item_modifier")) json = typeUntypedRanges(json);
		Object out = convert(json, null, worldgen);
		if (folder.equals("worldgen/biome") && out instanceof Map<?, ?> biome) out = biome((Map<String, Object>) biome);
		return out;
	}

	/**
	 * 26.2 read an untyped {"min": a, "max": b} number provider as a uniform one; 26.3 needs the type.
	 * Ranges inside predicates ("predicate", "range") are a different thing (bounds checks, never
	 * typed) and are left alone.
	 */
	@SuppressWarnings("unchecked")
	static Object typeUntypedRanges(Object node) {
		if (node instanceof List<?> list) {
			List<Object> out = new ArrayList<>();
			for (Object o : list) out.add(typeUntypedRanges(o));
			return out;
		}

		if (!(node instanceof Map<?, ?>)) return node;
		Map<String, Object> in = (Map<String, Object>) node;

		if (in.size() == 2 && in.get("min") instanceof Number && in.get("max") instanceof Number) {
			Map<String, Object> typed = new LinkedHashMap<>();
			typed.put("type", "minecraft:uniform");
			typed.putAll(in);
			return typed;
		}

		Map<String, Object> out = new LinkedHashMap<>();

		for (Map.Entry<String, Object> e : in.entrySet()) {
			String k = e.getKey();
			boolean bounds = k.equals("predicate") || k.equals("predicates") || k.equals("range");
			out.put(k, bounds ? e.getValue() : typeUntypedRanges(e.getValue()));
		}

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

	/**
	 * The same changes, undone: 26.3 data back to 26.2's format, for a 26.3 mod on a 26.2 game.
	 * Checked like the forward direction, by converting Mojang's 26.3 files and comparing them with
	 * Mojang's 26.2 files ({@code DataCheck 26.3 26.2 ...}).
	 */
	static final class Back {
		/** Loot condition types of 26.2 (from its vanilla data); used to recognise conditions written with "type". */
		private static final java.util.Set<String> CONDITIONS = java.util.Set.of("minecraft:all_of", "minecraft:any_of", "minecraft:match_block",
				"minecraft:damage_source_properties", "minecraft:enchantment_active_check", "minecraft:entity_properties", "minecraft:inverted",
				"minecraft:killed_by_player", "minecraft:location_check", "minecraft:match_tool", "minecraft:random_chance",
				"minecraft:random_chance_with_enchanted_bonus", "minecraft:survives_explosion", "minecraft:table_bonus", "minecraft:weather_check",
				"minecraft:reference", "minecraft:value_check", "minecraft:time_check", "minecraft:entity_scores", "minecraft:enchantment_level");
		private static final java.util.Set<String> STATE_PROVIDERS = java.util.Set.of("minecraft:simple", "minecraft:weighted", "minecraft:rule_based", "minecraft:randomized_int");
		private static final List<String> CATEGORIES = List.of("ambient", "axolotls", "creature", "misc", "monster", "underground_water_creature", "water_ambient", "water_creature");
		/** Named predicates that 26.3 added to vanilla; 26.2 has no such files, so they are written out in full. */
		private static final Map<String, String> VANILLA_PREDICATES = Map.of(
				"minecraft:tool/can_shear", "{\"type\":\"minecraft:match_tool\",\"predicate\":{\"items\":\"minecraft:shears\"}}",
				"minecraft:tool/can_silk_touch", "{\"type\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":{\"minecraft:enchantments\":[{\"enchantments\":\"minecraft:silk_touch\",\"levels\":{\"min\":1}}]}}}",
				"minecraft:block/fast_cooking", "{\"type\":\"minecraft:match_block\",\"blocks\":[\"minecraft:smoker\",\"minecraft:blast_furnace\"]}");

		@SuppressWarnings("unchecked")
		static Object migrate(String folder, Object json, boolean worldgen) {
			Object out = back(json, worldgen, false);
			if (folder.equals("worldgen/biome") && out instanceof Map<?, ?> biome) out = biome((Map<String, Object>) biome);
			return out;
		}

		private static boolean isCondition(Object o) {
			return o instanceof Map<?, ?> m && m.get("type") instanceof String t && CONDITIONS.contains(t);
		}

		/** A condition in a slot that used to hold a list: all_of is spread back into the list. */
		private static List<Object> asList(Object condition, boolean worldgen) {
			Object c = back(condition, worldgen, true);
			List<Object> list = new ArrayList<>();
			if (c instanceof Map<?, ?> m && "minecraft:all_of".equals(m.get("condition")) && m.get("terms") instanceof List<?> terms) list.addAll(terms);
			else list.add(c);
			return list;
		}

		@SuppressWarnings("unchecked")
		private static Object back(Object node, boolean worldgen, boolean conditionSlot) {
			if (node instanceof String ref && conditionSlot) {
				// 26.3 can name a predicate where 26.2 needs a condition object.
				String known = VANILLA_PREDICATES.get(ref);
				if (known != null) return back(com.isotope.util.Json.parse(known), worldgen, true);
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("condition", "minecraft:reference");
				r.put("name", ref);
				return r;
			}

			if (node instanceof List<?> list) {
				List<Object> out = new ArrayList<>();
				for (Object o : list) out.add(back(o, worldgen, conditionSlot));
				return out;
			}

			if (!(node instanceof Map<?, ?>)) return node;
			Map<String, Object> in = (Map<String, Object>) node;
			Map<String, Object> out = new LinkedHashMap<>();
			boolean isCond = conditionSlot && in.get("type") instanceof String;
			String trigger = in.get("trigger") instanceof String t ? t : null;

			for (Map.Entry<String, Object> e : in.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();

				if (k.equals("condition") && (isCondition(v) || v instanceof String)) {
					// Only real loot conditions: other files (animal variants) use "condition" for other things.
					out.put("conditions", asList(v, worldgen));
				} else if (k.equals("requirements") && isCondition(v)) {
					out.put(k, back(v, worldgen, true)); // enchantments: one condition, never a list
				} else if (k.equals("term") || (k.equals("terms") && isCond)) {
					out.put(k, back(v, worldgen, true));
				} else if (k.equals("modifier") && (v instanceof Map || v instanceof List)) {
					List<Object> fns = new ArrayList<>();
					for (Object f : v instanceof List<?> l ? l : List.of(v)) fns.add(function(back(f, worldgen, false)));
					out.put("functions", fns);
				} else if (k.equals("conditions") && v instanceof Map<?, ?> cond && trigger != null) {
					// Advancement trigger conditions: single conditions back into lists, renamed keys back.
					Map<String, Object> c = new LinkedHashMap<>();

					for (Map.Entry<String, Object> ce : ((Map<String, Object>) cond).entrySet()) {
						Object cv = ce.getValue();
						String ck = ce.getKey();
						if (ck.equals("recipes") && "minecraft:recipe_unlocked".equals(trigger)) ck = "recipe";
						else if (ck.equals("loot_tables")) ck = "loot_table";
						else if (ck.equals("blocks") && cv instanceof String) ck = "block";
						c.put(ck, isCondition(cv) ? asList(cv, worldgen) : back(cv, worldgen, false));
					}

					out.put(k, c);
				} else {
					out.put(k, back(v, worldgen, false));
				}
			}

			if (isCond) {
				out = rename(out, "type", "condition");

				if ("minecraft:match_block".equals(out.get("condition"))) {
					out.put("condition", "minecraft:block_state_property");
					out = rename(out, "blocks", "block");
					out = rename(out, "state", "properties");
				}
			}

			if (out.get("tags") instanceof List<?> tags) {
				List<Object> fixed = new ArrayList<>();

				for (Object t : tags) {
					if (t instanceof Map<?, ?> tm && tm.get("id") instanceof String id && tm.containsKey("expected") && id.startsWith("#")) {
						Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) tm);
						copy.put("id", id.substring(1));
						fixed.add(copy);
					} else {
						fixed.add(t);
					}
				}

				out.put("tags", fixed);
			}

			if ("minecraft:add".equals(out.get("type")) && out.containsKey("inputs")) {
				out.put("type", "minecraft:sum");
				out = rename(out, "inputs", "summands");
			}

			if (worldgen) {
				if (out.get("type") instanceof String t && out.containsKey("count") && out.containsKey("weight")) {
					Object count = out.get("count");
					Object min = null, max = null;
					if (count instanceof Number n) min = max = n;
					else if (count instanceof Map<?, ?> r && "minecraft:uniform".equals(r.get("type"))) { min = r.get("min_inclusive"); max = r.get("max_inclusive"); }

					if (min != null) {
						Map<String, Object> entry = new LinkedHashMap<>();
						for (Map.Entry<String, Object> e : out.entrySet()) {
							if (e.getKey().equals("count")) { entry.put("maxCount", max); entry.put("minCount", min); }
							else entry.put(e.getKey(), e.getValue());
						}
						out = entry;
					}
				}

				if (out.get("id") instanceof String id && id.indexOf(':') > 0 && !out.containsKey("Name") && (out.size() == 1 || out.containsKey("properties") && out.size() == 2)) {
					out = rename(out, "id", "Name");
					out = rename(out, "properties", "Properties");
				}

				if (out.get("type") instanceof String t && STATE_PROVIDERS.contains(t)) out.put("type", t + "_state_provider");
			}

			return out;
		}

		private static Object function(Object f) {
			if (!(f instanceof Map<?, ?> m) || m.containsKey("function") || !(m.get("type") instanceof String)) return f;
			@SuppressWarnings("unchecked")
			Map<String, Object> out = rename((Map<String, Object>) m, "type", "function");
			return out;
		}

		@SuppressWarnings("unchecked")
		private static Map<String, Object> biome(Map<String, Object> in) {
			if (!(in.get("attributes") instanceof Map<?, ?> a)) return in;
			Map<String, Object> attributes = new LinkedHashMap<>((Map<String, Object>) a);
			Map<String, Object> out = new LinkedHashMap<>(in);
			Object spawns = attributes.remove("minecraft:gameplay/natural_mob_spawns");

			if (spawns instanceof Map<?, ?> sm && sm.get("argument") instanceof Map<?, ?> arg) {
				Map<String, Object> spawners = new LinkedHashMap<>();
				Map<String, Object> byCategory = arg.get("spawns_by_category") instanceof Map<?, ?> b ? (Map<String, Object>) b : Map.of();
				for (String c : CATEGORIES) spawners.put(c, byCategory.getOrDefault(c, new ArrayList<>()));
				for (Map.Entry<String, Object> e : byCategory.entrySet()) spawners.putIfAbsent(e.getKey(), e.getValue());
				out.put("spawn_costs", arg.get("spawn_costs") instanceof Map<?, ?> c ? c : new LinkedHashMap<>());
				out.put("spawners", spawners);
			}

			Object probability = attributes.remove("minecraft:gameplay/creature_world_gen_spawn_probability");
			if (probability != null) out.put("creature_spawn_probability", probability);
			out.put("attributes", attributes);
			return out;
		}

		private static Map<String, Object> rename(Map<String, Object> m, String from, String to) {
			if (!m.containsKey(from)) return m;
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
			return out;
		}
	}
}
