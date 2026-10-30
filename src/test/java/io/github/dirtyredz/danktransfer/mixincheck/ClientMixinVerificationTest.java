package io.github.dirtyredz.danktransfer.mixincheck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Applies every configured client mixin to the exact pinned JEI/EMI bytecode without opening a client. */
class ClientMixinVerificationTest {
    private static final String CONFIG = "dank_recipe_transfer.mixins.json";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    @Test
    void clientMixinsApplyAndProduceVerifiableClasses() throws Exception {
        ClientConfig config = clientConfig();
        List<ClientMixinHarness.Target> targets = config.clientMixins().stream()
                .map(mixin -> new ClientMixinHarness.Target(config.mixinPackage() + "." + mixin,
                        annotatedTarget(config.mixinPackage() + "." + mixin)))
                .toList();

        List<ClientMixinHarness.Verification> verified = ClientMixinHarness.verify(CONFIG, targets);

        assertEquals(config.clientMixins(), verified.stream()
                        .map(result -> result.target().mixinClass().substring(config.mixinPackage().length() + 1))
                        .toList(),
                "Every client mixin in the production config must be transformed and verified");
        for (ClientMixinHarness.Verification result : verified) {
            String mixin = result.target().mixinClass();
            assertTrue(result.transformed(), mixin + " was not applied to " + result.target().targetClass());
            assertEquals("", result.diagnostics(), mixin + " failed ASM verification:\n" + result.diagnostics());
            assertNull(result.loadFailure(), mixin + " failed transformed-class loading");
            System.out.println("Verified client mixin: " + mixin + " -> " + result.target().targetClass());
        }
    }

    private static ClientConfig clientConfig() throws IOException {
        try (InputStream input = resource(CONFIG);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray client = config.getAsJsonArray("client");
            List<String> mixins = new ArrayList<>(client.size());
            client.forEach(element -> mixins.add(element.getAsString()));
            return new ClientConfig(config.get("package").getAsString(), List.copyOf(mixins));
        }
    }

    private static String annotatedTarget(String mixinClass) {
        List<String> targets = new ArrayList<>();
        try (InputStream input = resource(mixinClass.replace('.', '/') + ".class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!MIXIN_ANNOTATION.equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            if (!"value".equals(name)) {
                                return null;
                            }
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String ignored, Object value) {
                                    targets.add(((Type) value).getClassName());
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read client mixin " + mixinClass, e);
        }
        assertEquals(1, targets.size(), mixinClass + " must declare exactly one @Mixin value target");
        return targets.getFirst();
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IOException("Missing classpath resource " + name);
        }
        return input;
    }

    private record ClientConfig(String mixinPackage, List<String> clientMixins) {
    }
}
