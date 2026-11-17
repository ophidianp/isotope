package com.isotope.rewrite;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Runs one class through one hop: mixin strings first (old names), then the bytecode remap. */
public final class ClassRewriter {
	private ClassRewriter() { }

	/** Returns the same array when nothing in the class needed changing. */
	public static byte[] rewrite(byte[] classBytes, Hop hop, int[] changes) {
		return rewrite(classBytes, hop, changes, null);
	}

	/** @param stubsUsed collects a description of each stub applied (may be null) */
	public static byte[] rewrite(byte[] classBytes, Hop hop, int[] changes, java.util.Set<String> stubsUsed) {
		int before = changes[0];
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		String oldParent = node.superName; // before renaming, for keepSupertypes below
		MixinStrings.process(node, hop, changes);

		// COMPUTE_MAXS only recounts stack depth (needed when a POP is inserted after a call that
		// used to return void). Stack map frames are kept as written; renaming never invalidates them.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(new HopRemapper(writer, hop, changes, stubsUsed));
		byte[] out = changes[0] == before ? classBytes : writer.toByteArray();

		// Game classes that became interfaces (hand-written "x" rules): adapt direct subclasses.
		for (com.isotope.model.RedirectTable.InterfaceRule rule : hop.table.interfaceRules) {
			String iface = hop.table.mapClass(rule.cls());
			ClassNode cn = new ClassNode();
			new ClassReader(out).accept(cn, 0);
			if (cn.superName == null || !cn.superName.equals(iface)) continue;
			com.isotope.model.ClassInfo info = hop.live != null ? hop.live.get(iface) : null;

			if (InterfaceBridge.apply(cn, new com.isotope.model.RedirectTable.InterfaceRule(rule.cls(), rule.getter(),
					hop.mapDesc(rule.getterDesc()), rule.newName(), rule.newDesc()), iface, info)) {
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				out = w.toByteArray();
				changes[0]++;
			}
		}

		// Any mod class (not only direct subclasses) that still has the old type getter needs the new
		// codec() method, e.g. a subclass of RandomSpreadStructurePlacement in 26.3.
		for (com.isotope.model.RedirectTable.InterfaceRule rule : hop.table.interfaceRules) {
			ClassNode cn = new ClassNode();
			new ClassReader(out).accept(cn, 0);

			if (InterfaceBridge.addCodecBridge(cn, rule.getter(), hop.mapDesc(rule.getterDesc()), rule.newName(), rule.newDesc())) {
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				out = w.toByteArray();
				changes[0]++;
			}
		}

		// A game parent that lost an interface: the mod class declares it itself, as vanilla subclasses now do.
		if (oldParent != null && oldParent.startsWith("net/minecraft/")) {
			ClassNode cn = new ClassNode();
			new ClassReader(out).accept(cn, 0);

			if (InterfaceBridge.keepSupertypes(cn, oldParent, hop)) {
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				out = w.toByteArray();
				changes[0]++;
			}
		}

		// Interface methods that were reshaped (hand-written "a" rules): add the new one to implementers.
		for (com.isotope.model.RedirectTable.AdapterRule rule : hop.table.adapterRules) {
			String iface = hop.table.mapClass(rule.iface());
			ClassNode cn = new ClassNode();
			new ClassReader(out).accept(cn, 0);

			if (InterfaceBridge.adapt(cn, rule, iface, hop.mapDesc(rule.oldDesc()))) {
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				out = w.toByteArray();
				changes[0]++;
			}
		}

		return out;
	}
}
