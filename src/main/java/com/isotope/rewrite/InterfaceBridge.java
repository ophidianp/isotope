package com.isotope.rewrite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

	static List<String> list(String... s) {
		return new ArrayList<>(Arrays.asList(s));
	}
}
