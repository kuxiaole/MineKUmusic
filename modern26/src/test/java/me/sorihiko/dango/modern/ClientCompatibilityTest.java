package me.sorihiko.dango.modern;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import me.sorihiko.dango.protocol.MusicProtocol;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** Checks real game bytecode and valid synthetic music frames without starting Minecraft. */
public class ClientCompatibilityTest {
    private final String loader = System.getProperty("minekumusic.test.loader");

    @Test @SuppressWarnings("unchecked")
    public void payloadCodecPreservesTheServerPluginWireFormat() throws Exception {
        Class<?> payload = Class.forName("me.sorihiko.dango." + loader + ".ServerMusicPayload");
        var codec = (StreamCodec<RegistryFriendlyByteBuf, Object>) payload.getField("CODEC").get(null);
        JsonObject body = new JsonObject(); body.addProperty("chatJoin", true);
        byte[] frame = MusicProtocol.frames(MusicProtocol.message("hello", "synthetic-compatibility-request", body)).getFirst();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, payload.getConstructor(byte[].class).newInstance((Object) frame));
            byte[] encoded = new byte[buffer.readableBytes()]; buffer.getBytes(0, encoded);
            assertArrayEquals("Plugin messages must not acquire a loader-specific length prefix", frame, encoded);
            assertArrayEquals(frame, (byte[]) payload.getMethod("bytes").invoke(codec.decode(buffer)));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test
    public void renderMixinHooksExistInTheTargetGame() throws Exception {
        ClassNode mixin = bytecode("me/sorihiko/dango/" + loader + "/mixin/GameRendererMixin");
        ClassNode game = bytecode("net/minecraft/client/renderer/GameRenderer");
        int checked = 0;
        for (MethodNode method : mixin.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
            for (AnnotationNode inject : annotations) {
                if (!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                List<?> targets = (List<?>) value(inject, "method");
                List<MethodNode> methods = game.methods.stream().filter(m -> targets.contains(m.name) || targets.contains(m.name + m.desc)).toList();
                assertFalse("Missing game method for " + method.name, methods.isEmpty());
                for (Object annotation : (List<?>) value(inject, "at")) {
                    AnnotationNode at = (AnnotationNode) annotation;
                    if (!"INVOKE".equals(value(at, "value"))) continue;
                    String target = (String) value(at, "target");
                    boolean found = methods.stream().flatMap(m -> Stream.of(m.instructions.toArray()))
                            .filter(node -> node instanceof MethodInsnNode).map(node -> (MethodInsnNode) node)
                            .anyMatch(call -> target.equals("L" + call.owner + ";" + call.name + call.desc));
                    assertTrue("Missing render injection target: " + target, found);
                    checked++;
                }
            }
        }
        assertTrue("No render hooks were checked", checked > 0);
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation value: " + name);
    }
    private static ClassNode bytecode(String name) throws Exception {
        try (InputStream input = ClientCompatibilityTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull("Missing class: " + name, input);
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
}
