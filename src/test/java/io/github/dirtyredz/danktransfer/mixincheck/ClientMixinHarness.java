package io.github.dirtyredz.danktransfer.mixincheck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Headless Mixin transformation and bytecode verification for client-only mixins. */
public final class ClientMixinHarness {
    private ClientMixinHarness() {
    }

    /** A mixin class and the class named by its {@code @Mixin} annotation. */
    public record Target(String mixinClass, String targetClass) {
    }

    /** The observable result for one expected mixin. */
    public record Verification(Target target, boolean transformed, String diagnostics, Throwable loadFailure) {
    }

    /**
     * Applies the supplied mixins using a headless copy of {@code configName}. The copy drops the
     * production config plugin so optional-mod and physical-client gates cannot suppress the test.
     */
    public static List<Verification> verify(String configName, List<Target> targets) throws Exception {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one client mixin target is required");
        }

        ClientMixinTestService.installResource(configName, headlessConfig(configName));
        try {
            MixinBootstrap.init();
            IMixinTransformer transformer = newTransformer();
            MixinExtrasBootstrap.init();
            MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
            environment.setSide(MixinEnvironment.Side.CLIENT);
            addConfiguration(configName, environment);

            Map<String, byte[]> originals = new LinkedHashMap<>();
            Map<String, byte[]> transformed = new LinkedHashMap<>();
            for (Target target : targets) {
                originals.computeIfAbsent(target.targetClass(), ClientMixinHarness::uncheckedClassBytes);
            }
            for (Map.Entry<String, byte[]> target : originals.entrySet()) {
                transformed.put(target.getKey(),
                        transformer.transformClass(environment, target.getKey(), target.getValue()));
            }

            TransformedClassLoader verifierLoader = new TransformedClassLoader(transformed);
            Map<String, ClassVerification> classResults = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> target : transformed.entrySet()) {
                StringWriter diagnostics = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(target.getValue()), verifierLoader, false,
                        new PrintWriter(diagnostics));

                Throwable loadFailure = null;
                try {
                    Class.forName(target.getKey(), false, verifierLoader);
                } catch (Throwable failure) {
                    loadFailure = failure;
                }
                classResults.put(target.getKey(), new ClassVerification(
                        !Arrays.equals(originals.get(target.getKey()), target.getValue()),
                        diagnostics.toString(), loadFailure));
            }

            return targets.stream().map(target -> {
                ClassVerification result = classResults.get(target.targetClass());
                return new Verification(target, result.transformed(), result.diagnostics(), result.loadFailure());
            }).toList();
        } finally {
            ClientMixinTestService.removeResource(configName);
        }
    }

    private static byte[] headlessConfig(String configName) throws IOException {
        String json = new String(classBytes(configName), StandardCharsets.UTF_8);
        JsonObject config = JsonParser.parseReader(new StringReader(json)).getAsJsonObject();
        config.remove("plugin");
        return config.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static IMixinTransformer newTransformer() throws Exception {
        Class<?> type = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (IMixinTransformer) constructor.newInstance();
    }

    private static void addConfiguration(String configName, MixinEnvironment environment) throws Exception {
        Method add = Mixins.class.getDeclaredMethod("addConfiguration", String.class, MixinEnvironment.class);
        add.setAccessible(true);
        add.invoke(null, configName, environment);
    }

    private static byte[] uncheckedClassBytes(String name) {
        try {
            return classBytes(name.replace('.', '/') + ".class");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] classBytes(String resource) throws IOException {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing classpath resource " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private record ClassVerification(boolean transformed, String diagnostics, Throwable loadFailure) {
    }

    private static final class TransformedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private TransformedClassLoader(Map<String, byte[]> classes) {
            super(Thread.currentThread().getContextClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
