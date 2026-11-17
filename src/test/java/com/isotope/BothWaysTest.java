package com.isotope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.model.Hierarchy;
import com.isotope.model.RedirectTable;
import com.isotope.rewrite.ClassRewriter;
import com.isotope.rewrite.Hop;

/** Hand-written rules must work in both directions: forward as written, backward inverted. */
class BothWaysTest {
	private static final String FORWARD = """
			isotope-redirects 1
			k net/minecraft/world/level/block/Blocks WOOL WOOL net/minecraft/world/level/block/Block
			m net/minecraft/resources/RegistryFileCodec create (Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;)Lnet/minecraft/resources/RegistryFileCodec; holder (Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec; cast at=net/minecraft/core/registries/codec/RegistryCodecs
			c net/minecraft/resources/RegistryFileCodec net/minecraft/core/registries/codec/RegistryFileCodec
			""";

	private static Hop hop(RedirectTable t) throws Exception {
		return new Hop(t, Hierarchy.read(new StringReader("")), new HashMap<>());
	}

	private static byte[] method(java.util.function.Consumer<MethodVisitor> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/M", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "go", "()V", null, null);
		mv.visitCode();
		body.accept(mv);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<AbstractInsnNode> insns(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("go")).findFirst().orElseThrow();
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) if (i.getOpcode() >= 0) out.add(i);
		return out;
	}

	@Test
	void colorFieldBecomesCollectionAndBack() throws Exception {
		RedirectTable forward = RedirectTable.read(new StringReader(FORWARD));
		byte[] old = method(mv -> {
			mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/block/Blocks", "LIGHT_GRAY_WOOL", "Lnet/minecraft/world/level/block/Block;");
			mv.visitInsn(Opcodes.POP);
		});
		byte[] fwd = ClassRewriter.rewrite(old, hop(forward), new int[1]);
		List<AbstractInsnNode> f = insns(fwd);
		assertEquals("WOOL", ((FieldInsnNode) f.get(0)).name);
		assertEquals("lightGray", ((MethodInsnNode) f.get(1)).name);
		assertEquals(Opcodes.CHECKCAST, f.get(2).getOpcode());

		RedirectTable backward = new RedirectTable();
		backward.addInversesOf(forward);
		List<AbstractInsnNode> b = insns(ClassRewriter.rewrite(fwd, hop(backward), new int[1]));
		assertEquals("LIGHT_GRAY_WOOL", ((FieldInsnNode) b.get(0)).name);
		assertEquals(Opcodes.POP, b.get(1).getOpcode(), "accessor call and cast are gone");
	}

	@Test
	void movedStaticMethodMovesBack() throws Exception {
		RedirectTable backward = new RedirectTable();
		backward.addInversesOf(RedirectTable.read(new StringReader(FORWARD)));
		byte[] newer = method(mv -> {
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitInsn(Opcodes.ACONST_NULL);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/registries/codec/RegistryCodecs", "holder",
					"(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false);
			mv.visitInsn(Opcodes.POP);
		});
		MethodInsnNode call = (MethodInsnNode) insns(ClassRewriter.rewrite(newer, hop(backward), new int[1])).get(2);
		assertEquals("net/minecraft/resources/RegistryFileCodec", call.owner);
		assertEquals("create", call.name);
		assertEquals("(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;)Lnet/minecraft/resources/RegistryFileCodec;", call.desc);
	}
}
