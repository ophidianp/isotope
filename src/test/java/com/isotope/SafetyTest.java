package com.isotope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.link.Linker;
import com.isotope.link.LiveIndex;
import com.isotope.link.Report;
import com.isotope.link.StartupPath;
import com.isotope.model.Hierarchy;
import com.isotope.model.RedirectTable;
import com.isotope.rewrite.ClassRewriter;
import com.isotope.rewrite.Hop;
import com.isotope.rewrite.ModClass;

/** The parts of Isotope that decide whether a rewritten mod is safe to load. */
class SafetyTest {
	private static byte[] cls(String name, String superName, String[] interfaces, int access, java.util.function.Consumer<ClassWriter> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, access, name, null, superName, interfaces);
		body.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void method(ClassWriter cw, int access, String name, String desc, java.util.function.Consumer<MethodVisitor> code) {
		MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
		mv.visitCode();
		code.accept(mv);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static Hop stubHop(byte[]... mods) throws Exception {
		RedirectTable t = RedirectTable.read(new StringReader("""
				isotope-redirects 1
				s m net/minecraft/Base gone ()Ljava/lang/Object; removed for the test
				s f net/minecraft/Base CODEC Ljava/lang/Object; removed for the test
				"""));
		Hierarchy h = Hierarchy.read(new StringReader("net/minecraft/Base -\nnet/minecraft/Sub net/minecraft/Base\n"));
		Map<String, ModClass> m = new HashMap<>();
		for (byte[] b : mods) m.put(ModClass.scan(b).name, ModClass.scan(b));
		return new Hop(t, h, m);
	}

	private static List<Integer> opcodes(byte[] bytes, String method) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode mn = cn.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) if (i.getOpcode() >= 0) out.add(i.getOpcode());
		return out;
	}

	@Test
	void stubReplacesCallsAndFieldReadsOfRemovedApisWithNull() throws Exception {
		byte[] in = cls("mod/User", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_STATIC, "go", "()V", mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/Sub", "gone", "()Ljava/lang/Object;", false); // found through Sub's parent
			mv.visitInsn(Opcodes.POP);
			mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/Sub", "CODEC", "Ljava/lang/Object;");
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
		}));
		Set<String> used = new HashSet<>();
		byte[] out = ClassRewriter.rewrite(in, stubHop(in), new int[1], used);
		assertEquals(List.of(Opcodes.ACONST_NULL, Opcodes.POP, Opcodes.ACONST_NULL, Opcodes.POP, Opcodes.RETURN), opcodes(out, "go"));
		assertEquals(2, used.size());
	}

	@Test
	void aModsOwnFieldIsNeverStubbed() throws Exception {
		byte[] block = cls("mod/MyBlock", "net/minecraft/Base", null, Opcodes.ACC_PUBLIC, cw -> {
			cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, "CODEC", "Ljava/lang/Object;", null, null).visitEnd();
			method(cw, Opcodes.ACC_STATIC, "go", "()V", mv -> {
				mv.visitFieldInsn(Opcodes.GETSTATIC, "mod/MyBlock", "CODEC", "Ljava/lang/Object;");
				mv.visitInsn(Opcodes.POP);
				mv.visitInsn(Opcodes.RETURN);
			});
		});
		assertEquals(List.of(Opcodes.GETSTATIC, Opcodes.POP, Opcodes.RETURN), opcodes(ClassRewriter.rewrite(block, stubHop(block), new int[1]), "go"));
	}

	private static LiveIndex index(Map<String, byte[]> live) {
		return new LiveIndex(List.of(live::get, n -> {
			try (var in = ClassLoader.getSystemResourceAsStream(n + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException e) {
				return null;
			}
		}));
	}

	/** A mod class whose static initializer makes a lambda {@code () -> null} for {@code iface.codec()}. */
	private static byte[] lambdaUser(String iface) {
		return cls("mod/Types", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> {
			method(cw, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$0", "()Ljava/lang/Object;", mv -> {
				mv.visitInsn(Opcodes.ACONST_NULL);
				mv.visitInsn(Opcodes.ARETURN);
			});
			method(cw, Opcodes.ACC_STATIC, "<clinit>", "()V", mv -> {
				org.objectweb.asm.Handle lmf = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
				org.objectweb.asm.Type sam = org.objectweb.asm.Type.getMethodType("()Ljava/lang/Object;");
				mv.visitInvokeDynamicInsn("codec", "()L" + iface + ";", lmf, sam,
						new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "mod/Types", "lambda$0", "()Ljava/lang/Object;", false), sam);
				mv.visitInsn(Opcodes.POP);
				mv.visitInsn(Opcodes.RETURN);
			});
		});
	}

	@Test
	void aLambdaForAnInterfaceWithNoAbstractMethodsLeftIsNotAProblem() {
		// 26.3's StructureProcessorType kept its name but lost codec(): old lambdas still load.
		Map<String, byte[]> live = new HashMap<>();
		live.put("net/minecraft/ProcType", cls("net/minecraft/ProcType", "java/lang/Object", null,
				Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, cw -> { }));
		Report report = new Report("t.jar");
		new Linker(index(live), report).check(lambdaUser("net/minecraft/ProcType"));
		assertFalse(report.render().contains("implemented by a lambda"), report.render());

		// But if the interface now has a different abstract method, the lambda cannot implement it.
		live.put("net/minecraft/ProcType", cls("net/minecraft/ProcType", "java/lang/Object", null,
				Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, cw ->
						cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "other", "()V", null, null).visitEnd()));
		Report report2 = new Report("t.jar");
		new Linker(index(live), report2).check(lambdaUser("net/minecraft/ProcType"));
		assertTrue(report2.render().contains("implemented by a lambda"), report2.render());
	}

	@Test
	void anInjectWhoseTargetChangedParametersIsReportedAndDisarmed() {
		// Real case: Moonlight's @Inject into ChunkGenerator.getMobsAt(Holder, ...); 26.3 made it getMobsAt(Level, ...).
		Map<String, byte[]> live = new HashMap<>();
		live.put("net/minecraft/Gen", cls("net/minecraft/Gen", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw ->
				method(cw, Opcodes.ACC_PUBLIC, "getMobsAt", "(Lnet/minecraft/Level;I)V", mv -> mv.visitInsn(Opcodes.RETURN))));
		live.put("net/minecraft/Holder", cls("net/minecraft/Holder", "java/lang/Object", null, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, cw -> { }));
		live.put("net/minecraft/Level", cls("net/minecraft/Level", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> { }));
		String ci = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
		java.util.function.BiFunction<String, String, byte[]> mixin = (name, desc) -> cls(name, "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> {
			var m = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
			var arr = m.visitArray("value");
			arr.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/Gen"));
			arr.visitEnd();
			m.visitEnd();
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "hook", desc, null, null);
			var an = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
			var ma = an.visitArray("method");
			ma.visit(null, "getMobsAt");
			ma.visitEnd();
			an.visitEnd();
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 4);
			mv.visitEnd();
		});

		byte[] stale = mixin.apply("mod/StaleMixin", "(Lnet/minecraft/Holder;I" + ci + "Ljava/lang/Object;)V");
		Report report = new Report("t.jar");
		com.isotope.link.MixinCheck.check(stale, index(live), report);
		assertTrue(report.render().contains("the hook cannot apply"), report.render());
		ClassNode cn = new ClassNode();
		new ClassReader(stale).accept(cn, 0);
		assertTrue(com.isotope.link.MixinCheck.disarmMismatched(cn, List.of("net/minecraft/Gen"), index(live)));
		assertTrue(cn.methods.stream().filter(x -> x.name.equals("hook")).allMatch(x -> x.visibleAnnotations == null || x.visibleAnnotations.isEmpty()));

		// Handlers that fit (target parameters, or only the callback info) are left alone.
		for (String desc : List.of("(Lnet/minecraft/Level;I" + ci + ")V", "(" + ci + ")V")) {
			byte[] fine = mixin.apply("mod/FineMixin", desc);
			Report r = new Report("t.jar");
			com.isotope.link.MixinCheck.check(fine, index(live), r);
			assertFalse(r.render().contains("the hook cannot apply"), r.render());
			ClassNode fn = new ClassNode();
			new ClassReader(fine).accept(fn, 0);
			assertFalse(com.isotope.link.MixinCheck.disarmMismatched(fn, List.of("net/minecraft/Gen"), index(live)));
		}
	}

	@Test
	void implementingANowSealedInterfaceIsFatal() {
		ClassWriter sealed = new ClassWriter(0);
		sealed.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "net/minecraft/Holder", null, "java/lang/Object", null);
		sealed.visitPermittedSubclass("net/minecraft/Holder$Direct");
		sealed.visitEnd();
		Map<String, byte[]> live = new HashMap<>();
		live.put("net/minecraft/Holder", sealed.toByteArray());
		byte[] mod = cls("mod/MyHolder", "java/lang/Object", new String[] {"net/minecraft/Holder"}, Opcodes.ACC_PUBLIC, cw -> { });
		live.put("mod/MyHolder", mod);
		Report report = new Report("t.jar");
		new Linker(index(live), report).check(mod);
		assertTrue(report.render().contains("is now sealed"), report.render());
		assertTrue(report.hasFatal());
	}

	@Test
	void startupPathFollowsInitializerIntoCreatedOverridesOnly() {
		// onInitialize -> Helper.run() (virtual) -> HelperImpl.run() calls a game method that is gone.
		// UnusedImpl overrides run() too, but the mod never creates it, so it must not be blamed.
		Map<String, byte[]> own = new HashMap<>();
		own.put("mod/Init", cls("mod/Init", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_PUBLIC, "onInitialize", "()V", mv -> {
			mv.visitTypeInsn(Opcodes.NEW, "mod/HelperImpl");
			mv.visitInsn(Opcodes.DUP);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "mod/HelperImpl", "<init>", "()V", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "mod/Helper", "run", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
		})));
		own.put("mod/Helper", cls("mod/Helper", "java/lang/Object", null, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
				cw -> cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd()));
		for (String impl : List.of("mod/HelperImpl", "mod/UnusedImpl")) {
			own.put(impl, cls(impl, "mod/Helper", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_PUBLIC, "run", "()V", mv -> mv.visitInsn(Opcodes.RETURN))));
		}

		Map<String, List<String>> problems = new HashMap<>();
		problems.put("mod/HelperImpl run()V", List.of("net.minecraft.Gone"));
		problems.put("mod/UnusedImpl run()V", List.of("net.minecraft.AlsoGone"));
		Map<String, List<String>> found = StartupPath.find(own, List.of("mod/Init"), problems, false);
		assertEquals(Set.of("mod.HelperImpl.run"), found.keySet());
		assertFalse(found.containsKey("mod.UnusedImpl.run"));
	}
}
