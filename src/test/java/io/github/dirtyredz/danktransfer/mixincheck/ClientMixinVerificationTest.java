package io.github.dirtyredz.danktransfer.mixincheck;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.junit.jupiter.api.Test;
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
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Applies every client mixin to the exact pinned JEI/EMI bytecode without opening a client. */
class ClientMixinVerificationTest {
    private static final Map<String, String> TARGETS = Map.of(
            "mezz.jei.library.recipes.RecipeTransferManager", "JeiTransferLookupMixin",
            "dev.emi.emi.registry.EmiRecipeFiller", "EmiHandlerLookupMixin",
            "dev.emi.emi.api.widget.RecipeFillButtonWidget", "EmiFillButtonMixin",
            "dev.emi.emi.widget.RecipeButtonWidget", "RecipeButtonWidgetAccessor"
    );

    @Test
    void clientMixinsApplyAndProduceVerifiableClasses() throws Exception {
        MixinBootstrap.init();
        IMixinTransformer transformer = newTransformer();
        MixinExtrasBootstrap.init();
        MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        addConfiguration(environment);

        Map<String, byte[]> transformed = new LinkedHashMap<>();
        for (Map.Entry<String, String> target : TARGETS.entrySet()) {
            byte[] original = classBytes(target.getKey());
            byte[] result = transformer.transformClass(environment, target.getKey(), original);
            assertTrue(result.length > 0, target.getValue() + " produced no bytecode");
            assertFalse(Arrays.equals(original, result), target.getValue() + " was not applied");
            transformed.put(target.getKey(), result);
        }

        TransformedClassLoader verifierLoader = new TransformedClassLoader(transformed);
        for (Map.Entry<String, String> target : TARGETS.entrySet()) {
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(transformed.get(target.getKey())), verifierLoader, false,
                    new PrintWriter(diagnostics));
            assertEquals("", diagnostics.toString(), target.getValue() + " failed ASM verification:\n" + diagnostics);
            Class.forName(target.getKey(), false, verifierLoader);
            System.out.println("Verified client mixin: " + target.getValue() + " -> " + target.getKey());
        }
    }

    private static IMixinTransformer newTransformer() throws Exception {
        Class<?> type = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (IMixinTransformer) constructor.newInstance();
    }

    private static void addConfiguration(MixinEnvironment environment) throws Exception {
        Method add = Mixins.class.getDeclaredMethod("addConfiguration", String.class, MixinEnvironment.class);
        add.setAccessible(true);
        add.invoke(null, "dank_recipe_transfer.client-mixin-test.json", environment);
    }

    private static byte[] classBytes(String name) throws IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing pinned viewer class " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            return output.toByteArray();
        }
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
