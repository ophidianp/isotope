package com.isotope.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import com.isotope.model.ClassInfo;

/**
 * Runs the same type check the JVM runs before a class may be used, and rescues the class
 * when only some of its methods fail it.
 *
 * <p>Before any method of a class runs, the JVM verifies <em>every</em> method in it. To
 * check that a value fits where it is passed ("is a UniformGenerator a NumberProvider?")
 * it has to load both classes. If one of them no longer exists in this game version, the
 * whole class fails with NoClassDefFoundError, including methods that never touch the
 * missing class. Real case: owo-lib's {@code LootOps.registerListener()} is fine on 26.3,
 * but a sibling method uses the loot number providers 26.3 redesigned, so owo could not
 * even start.
 *
 * <p>This finds exactly the methods that need a missing game class to be verified. Their
 * bodies are replaced with one line that throws a clear error naming what is missing. Those
 * methods could never have run anyway (their class could not load), so this is strictly
 * better: the rest of the class now works, and the replaced methods fail only if called.
 *
 * <p>Only missing classes in the game's own packages count. A method that needs an optional
 * mod that is not installed is left alone: its class is normally only loaded when that mod
 * is present, and replacing it would stick in the cache even after the mod is installed.
 */
public final class VerifyCheck {
	private VerifyCheck() { }

	/** Thrown inside the analysis when a type the check needs does not exist. */
	private static final class Missing extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final String name;

		Missing(String name) {
			super(null, null, false, false);
			this.name = name;
		}
	}

	public static final class Result {
		/** The class bytes, with failing methods replaced (or the input when nothing was). */
		public byte[] bytes;
		/** "name+desc" -> the missing classes it needed. */
		public final Map<String, Set<String>> replaced = new LinkedHashMap<>();
	}

	/**
	 * @param replace false only reports (strict mode); true also replaces the failing methods
	 * @param message a short phrase for the thrown error, e.g. "cannot run on Minecraft 26.3"
	 */
	public static Result check(byte[] classBytes, LiveIndex index, boolean replace, String message) {
		Result result = new Result();
		result.bytes = classBytes;
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);
		Type self = Type.getObjectType(cn.name);
		Type superType = cn.superName == null ? null : Type.getObjectType(cn.superName);
		List<Type> interfaces = new ArrayList<>();
		for (String i : cn.interfaces) interfaces.add(Type.getObjectType(i));
		boolean isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0;

		for (MethodNode mn : cn.methods) {
			if (mn.instructions.size() == 0) continue; // abstract or native
			Set<String> missing = new LinkedHashSet<>();
			IndexVerifier verifier = new IndexVerifier(self, superType, interfaces, isInterface, index, missing);

			try {
				new Analyzer<>(verifier).analyze(cn.name, mn);
			} catch (AnalyzerException e) {
				// Only our own "missing class" signal counts. Any other complaint from the analysis
				// is not proof the JVM would reject the method, so it is ignored.
				if (rootCause(e) instanceof Missing m && m.name != null) missing.add(m.name);
				else missing.clear();
			} catch (Missing e) {
				if (e.name != null) missing.add(e.name);
			} catch (RuntimeException e) {
				missing.clear();
			}

			missing.removeIf(name -> !Linker.isGameName(name));
			if (!missing.isEmpty()) result.replaced.put(mn.name + mn.desc, missing);
		}

		if (result.replaced.isEmpty() || !replace) return result;

		for (MethodNode mn : cn.methods) {
			Set<String> missing = result.replaced.get(mn.name + mn.desc);
			if (missing == null) continue;
			String text = "Isotope: " + cn.name.replace('/', '.') + "." + mn.name + " " + message + ": "
					+ String.join(", ", missing).replace('/', '.') + " no longer exists";
			InsnList body = new InsnList();
			body.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IncompatibleClassChangeError"));
			body.add(new InsnNode(Opcodes.DUP));
			body.add(new LdcInsnNode(text));
			body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false));
			body.add(new InsnNode(Opcodes.ATHROW));
			mn.instructions = body;
			mn.tryCatchBlocks.clear();
			mn.localVariables = null;
			mn.visibleLocalVariableAnnotations = null;
			mn.invisibleLocalVariableAnnotations = null;
			mn.maxStack = 3;
			mn.maxLocals = (Type.getArgumentsAndReturnSizes(mn.desc) >> 2) - ((mn.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
		}

		// COMPUTE_MAXS only: the new bodies have no branches, so they need no stack map frames,
		// and every other method keeps the frames it was compiled with.
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(w);
		result.bytes = w.toByteArray();
		return result;
	}

	private static Throwable rootCause(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
	}

	/**
	 * ASM's verifier, answering "is A a subtype of B?" from the classes that really exist
	 * instead of loading them. Mirrors the JVM's rules: assigning to Object or to an interface
	 * needs no lookup (the JVM does not check interfaces at this point); assigning to a class
	 * needs both classes. Merging two types at a branch join (which the JVM does not do,
	 * because it reads the frames compiled into the class) never counts as a failure.
	 */
	private static final class IndexVerifier extends SimpleVerifier {
		private final LiveIndex index;
		private final Set<String> missing;
		private final Type self;
		private final Type selfSuper;
		private boolean merging;

		IndexVerifier(Type self, Type superType, List<Type> interfaces, boolean isInterface, LiveIndex index, Set<String> missing) {
			super(ASM9, self, superType, interfaces, isInterface);
			this.index = index;
			this.missing = missing;
			this.self = self;
			this.selfSuper = superType;
		}

		private ClassInfo info(Type t) {
			ClassInfo c = index.get(t.getInternalName());

			if (c == null) {
				if (merging) throw new Missing(null); // caught in merge(); not a real failure
				throw new Missing(t.getInternalName());
			}

			return c;
		}

		@Override
		public BasicValue merge(BasicValue a, BasicValue b) {
			boolean was = merging;
			merging = true;

			try {
				return super.merge(a, b);
			} catch (Missing e) {
				return a.equals(b) ? a : newValue(Type.getObjectType("java/lang/Object"));
			} finally {
				merging = was;
			}
		}

		@Override
		protected boolean isInterface(Type type) {
			if (type.equals(self)) return super.isInterface(type);
			return info(type).isInterface();
		}

		@Override
		protected Type getSuperClass(Type type) {
			if (type.equals(self)) return selfSuper;
			String s = info(type).superName;
			return s == null ? null : Type.getObjectType(s);
		}

		@Override
		protected boolean isAssignableFrom(Type target, Type value) {
			if (target.equals(value)) return true;
			if (target.getSort() == Type.OBJECT && target.getInternalName().equals("java/lang/Object")) return true;

			if (target.getSort() == Type.ARRAY || value.getSort() == Type.ARRAY) {
				if (target.getSort() != Type.ARRAY || value.getSort() != Type.ARRAY) return false;
				Type te = Type.getType(target.getDescriptor().substring(1));
				Type ve = Type.getType(value.getDescriptor().substring(1));
				if (te.getSort() < Type.ARRAY || ve.getSort() < Type.ARRAY) return te.equals(ve);
				return isAssignableFrom(te, ve);
			}

			if (target.getSort() != Type.OBJECT || value.getSort() != Type.OBJECT) return false;
			if (isInterface(target)) return true; // the JVM's verifier treats interfaces like Object
			Type t = value;

			for (int depth = 0; t != null && depth < 64; depth++) {
				if (t.equals(target)) return true;
				t = getSuperClass(t);
			}

			return false;
		}

		@Override
		protected Class<?> getClass(Type type) {
			// Never load classes through reflection: everything is answered from the index above.
			throw new UnsupportedOperationException(type.getInternalName());
		}
	}
}
