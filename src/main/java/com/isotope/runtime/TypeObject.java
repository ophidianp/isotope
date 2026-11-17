package com.isotope.runtime;

/**
 * Stands in for game "type" interfaces that a version removed (26.3's StructurePlacementType).
 *
 * <p>Those interfaces had a single method, {@code codec()}, and old mods implement them with
 * lambdas such as {@code () -> CODEC}. A redirect rule renames the removed interface to this
 * one, so the lambdas still load; {@link Bridge#codecOf} then reads the codec back out.
 * Its only dependency is DataFixerUpper's MapCodec, a library that every 26.x ships.
 */
@FunctionalInterface
public interface TypeObject {
	com.mojang.serialization.MapCodec<?> codec();
}
