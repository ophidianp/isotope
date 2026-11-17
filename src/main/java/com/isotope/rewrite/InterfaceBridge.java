package com.isotope.rewrite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import com.isotope.model.ClassInfo;
import com.isotope.model.RedirectTable;

/**
 * Adapts a mod class whose game parent class became an interface.
 *
 * <p>Example (26.1.2 to 26.2): {@code StructureProcessor} was an abstract class whose
 * subclasses implemented {@code getType()}, returning a small "type" object that hands out
 * the codec. In 26.2 it is an interface whose implementations return the codec directly
 * from {@code codec()}. For each mod class that directly extended it, this:
 * <ol>
 * <li>makes it implement the interface and extend {@code Object} instead, pointing its
 *     constructor's {@code super()} call at {@code Object};</li>
 * <li>makes the methods it overrides public (interface methods always are);</li>
 * <li>adds the new {@code codec()} method, which asks the mod's own {@code getType()}
 *     object for the codec through {@code com.isotope.runtime.Bridge}.</li>
 * </ol>
 * Each such change of the game is declared by a hand-written {@code x} rule.
 */
public final class InterfaceBridge {
	private InterfaceBridge() { }

	/** @param iface the interface as it exists in the running game (for the list of methods to make public) */
	public static boolean apply(ClassNode cn, RedirectTable.InterfaceRule rule, String ifaceName, ClassInfo iface) {
		if (cn.superName == null || !cn.superName.equals(ifaceName) || (cn.access & Opcodes.ACC_INTERFACE) != 0) return false;
		cn.superName = "java/lang/Object";
		if (cn.interfaces == null) cn.interfaces = new ArrayList<>();
		if (!cn.interfaces.contains(ifaceName)) cn.interfaces.add(ifaceName);

		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(ifaceName)) {
					if (call.name.equals("<init>")) {
						if (!call.desc.equals("()V")) throw new IllegalStateException("old " + ifaceName + " constructor took arguments; cannot bridge");
						call.owner = "java/lang/Object"; // the interface has no constructor; Object's is what is left
					} else {
						call.itf = true; // super.m() is now a call to the interface's default method
					}
				}
			}

			// Interface methods are public: an override with less access would fail when called.
			boolean overridesInterface = iface != null && iface.methods.containsKey(mn.name + mn.desc);
			if (overridesInterface && (mn.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
				mn.access = (mn.access & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
			}
		}

		boolean hasGetter = cn.methods.stream().anyMatch(m -> m.name.equals(rule.getter()) && m.desc.equals(rule.getterDesc()));
		boolean hasNew = cn.methods.stream().anyMatch(m -> m.name.equals(rule.newName()) && m.desc.equals(rule.newDesc()));
		boolean isAbstract = (cn.access & Opcodes.ACC_ABSTRACT) != 0;

		if (!hasGetter && isAbstract) {
			// The getter used to be declared by the game class; keep a declaration so subclasses still
			// override it and the bridge below can call it.
			cn.methods.add(new MethodNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, rule.getter(), rule.getterDesc(), null, null));
		}

		if (!hasNew && (hasGetter || isAbstract)) {
			MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, rule.newName(), rule.newDesc(), null, null);
			bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cn.name, rule.getter(), rule.getterDesc(), false));
			bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/isotope/runtime/Bridge", "codecOf", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
			bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(rule.newDesc()).getInternalName()));
			bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
			bridge.maxStack = 1;
			bridge.maxLocals = 1;
			cn.methods.add(bridge);
		}

		return true;
	}

	/**
	 * Adds {@code codec()} to a class that implements the old type getter itself and has no
	 * {@code codec()} yet. Used for classes further down the hierarchy than the direct subclasses
	 * {@link #apply} handles. Interfaces and classes without the getter are left alone.
	 */
	public static boolean addCodecBridge(ClassNode cn, String getter, String getterDesc, String newName, String newDesc) {
		if ((cn.access & Opcodes.ACC_INTERFACE) != 0) return false;
		boolean hasGetter = cn.methods.stream().anyMatch(m -> m.name.equals(getter) && m.desc.equals(getterDesc) && (m.access & Opcodes.ACC_ABSTRACT) == 0);
		boolean hasNew = cn.methods.stream().anyMatch(m -> m.name.equals(newName));
		if (!hasGetter || hasNew) return false;
		MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, newName, newDesc, null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cn.name, getter, getterDesc, false));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/isotope/runtime/Bridge", "codecOf", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(newDesc).getInternalName()));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 1;
		bridge.maxLocals = 1;
		cn.methods.add(bridge);
		return true;
	}

	/**
	 * Adds the reshaped method of an interface the class implements directly, as a call to the
	 * class's own old method. The new and old methods must take the same arguments; the old result
	 * is returned as is (it must fit the new return type) or wrapped with {@code Stream.of}.
	 */
	public static boolean adapt(ClassNode cn, RedirectTable.AdapterRule rule, String iface, String oldDescMapped) {
		if (cn.interfaces == null || !cn.interfaces.contains(iface) || (cn.access & Opcodes.ACC_INTERFACE) != 0) return false;
		boolean hasOld = cn.methods.stream().anyMatch(m -> m.name.equals(rule.oldName()) && m.desc.equals(oldDescMapped) && (m.access & Opcodes.ACC_ABSTRACT) == 0);
		boolean hasNew = cn.methods.stream().anyMatch(m -> m.name.equals(rule.newName()) && m.desc.equals(rule.newDesc()));
		if (!hasOld || hasNew) return false;
		Type[] oldArgs = Type.getArgumentTypes(oldDescMapped);
		Type[] newArgs = Type.getArgumentTypes(rule.newDesc());
		Type[] expected = rule.forEach() ? Arrays.copyOf(oldArgs, oldArgs.length + 1) : oldArgs;
		if (rule.forEach()) expected[oldArgs.length] = Type.getObjectType("java/util/function/Consumer");
		if (!Arrays.equals(expected, newArgs)) throw new IllegalStateException("adapter rule arguments do not line up: " + rule);

		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, rule.newName(), rule.newDesc(), null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 1;

		for (Type a : oldArgs) {
			m.instructions.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
			slot += a.getSize();
		}

		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cn.name, rule.oldName(), oldDescMapped, false));
		int locals = slot;

		if (rule.forEach()) {
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot)); // the consumer
			m.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "forEach", "(Ljava/util/function/Consumer;)V", true));
			m.instructions.add(new InsnNode(Opcodes.RETURN));
			locals = slot + 1;
		} else {
			if (rule.wrapStream()) m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "of", "(Ljava/lang/Object;)Ljava/util/stream/Stream;", true));
			m.instructions.add(new InsnNode(Opcodes.ARETURN));
		}

		m.maxStack = locals;
		m.maxLocals = locals;
		cn.methods.add(m);
		return true;
	}

	/**
	 * Keeps a mod class's supertypes when its game parent lost some. Example (26.3): the mod's
	 * class extended AbstractPackResources, which implemented PackResources. 26.3 renamed it to
	 * AbstractPackMetadataResources, which no longer does; vanilla subclasses now add
	 * "implements PackResources" themselves. The mod class gets the same treatment, otherwise it
	 * silently stops being a PackResources and fails with ClassCastException where it is used.
	 *
	 * @param oldParent the parent's OLD name (in the hop's old hierarchy)
	 * @return true if interfaces were added
	 */
	public static boolean keepSupertypes(ClassNode cn, String oldParent, Hop hop) {
		if (hop.live == null || cn.superName == null || (cn.access & Opcodes.ACC_INTERFACE) != 0) return false;
		java.util.Set<String> before = new java.util.LinkedHashSet<>();
		collectOld(oldParent, hop, before, 0);
		java.util.Set<String> after = new java.util.HashSet<>();
		collectLive(cn.superName, hop, after, 0);
		boolean changed = false;

		for (String oldIface : before) {
			String iface = hop.table.mapClass(oldIface);
			if (after.contains(iface) || !iface.startsWith("net/minecraft/")) continue;
			ClassInfo info = hop.live.get(iface);
			if (info == null || !info.isInterface()) continue; // gone or no longer an interface: reported elsewhere
			if (cn.interfaces == null) cn.interfaces = new ArrayList<>();

			if (!cn.interfaces.contains(iface)) {
				cn.interfaces.add(iface);
				changed = true;
			}
		}

		return changed;
	}

	/** Interfaces of a class in the hop's OLD game version, including inherited ones. */
	private static void collectOld(String cls, Hop hop, java.util.Set<String> out, int depth) {
		if (cls == null || depth > 32) return;
		com.isotope.model.Hierarchy.Node n = hop.oldHierarchy().get(cls);
		if (n == null) return;

		for (String i : n.interfaces()) {
			if (out.add(i)) collectOld(i, hop, out, depth + 1);
		}

		collectOld(n.superName(), hop, out, depth + 1);
	}

	/** Interfaces of a class in the running game, including inherited ones. */
	private static void collectLive(String cls, Hop hop, java.util.Set<String> out, int depth) {
		if (cls == null || depth > 32) return;
		ClassInfo c = hop.live.get(cls);
		if (c == null) return;

		for (String i : c.interfaces) {
			if (out.add(i)) collectLive(i, hop, out, depth + 1);
		}

		collectLive(c.superName, hop, out, depth + 1);
	}

	/**
	 * Going back to a version before color collections: {@code getstatic Blocks.WOOL;
	 * invokevirtual ColorCollection.white()} (and the cast that usually follows) becomes
	 * {@code getstatic Blocks.WHITE_WOOL}. Other uses of a collection (iterating it, storing it)
	 * have no old equivalent and are left for the link check to report.
	 */
	public static int reverseColors(ClassNode cn, Map<String, RedirectTable.ColorRule> reverse) {
		int changed = 0;

		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode get) || get.getOpcode() != Opcodes.GETSTATIC
						|| !get.desc.equals("Lnet/minecraft/world/level/block/ColorCollection;")) continue;
				RedirectTable.ColorRule rule = reverse.get(get.owner + ' ' + get.name);
				AbstractInsnNode next = nextReal(get);
				if (rule == null || !(next instanceof MethodInsnNode call) || !call.owner.equals("net/minecraft/world/level/block/ColorCollection")
						|| !call.desc.equals("()Ljava/lang/Object;")) continue;
				String color = colorConstant(call.name);
				if (color == null) continue;
				AbstractInsnNode after = nextReal(call);
				mn.instructions.set(get, new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, rule.owner(), color + "_" + rule.suffix(), "L" + rule.elementType() + ";"));
				mn.instructions.remove(call);
				if (after instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(rule.elementType())) mn.instructions.remove(cast);
				changed++;
				insn = mn.instructions.getFirst(); // the list changed under us; rescanning is cheap
			}
		}

		return changed;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode n) {
		AbstractInsnNode x = n.getNext();
		while (x != null && x.getOpcode() < 0) x = x.getNext();
		return x;
	}

	/** "lightBlue" -> "LIGHT_BLUE"; null for anything that is not one of the 16 color accessors. */
	static String colorConstant(String accessor) {
		String c = accessor.replaceAll("([A-Z])", "_$1").toUpperCase(java.util.Locale.ROOT);
		for (String known : RedirectTable.COLORS) if (known.equals(c)) return c;
		return null;
	}

	static List<String> list(String... s) {
		return new ArrayList<>(Arrays.asList(s));
	}
}
