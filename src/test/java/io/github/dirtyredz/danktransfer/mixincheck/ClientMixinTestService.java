package io.github.dirtyredz.danktransfer.mixincheck;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal classpath-backed Mixin service used by {@link ClientMixinHarness}. */
public final class ClientMixinTestService extends MixinServiceAbstract implements IClassProvider,
        IClassBytecodeProvider, ITransformerProvider, IClassTracker, IMixinAuditTrail {
    private static final Map<String, byte[]> RESOURCE_OVERRIDES = new ConcurrentHashMap<>();
    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();

    static void installResource(String name, byte[] contents) {
        RESOURCE_OVERRIDES.put(name, contents.clone());
    }

    static void removeResource(String name) {
        RESOURCE_OVERRIDES.remove(name);
    }

    @Override
    public String getName() {
        return "Headless client mixin verifier";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return this;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("headless-client-mixin-test");
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] override = RESOURCE_OVERRIDES.get(name);
        if (override != null) {
            return new ByteArrayInputStream(override);
        }
        return loader().getResourceAsStream(name);
    }

    @Override
    public URL[] getClassPath() {
        return System.getProperty("java.class.path").lines()
                .flatMap(line -> List.of(line.split(java.io.File.pathSeparator)).stream())
                .map(Path::of)
                .map(Path::toUri)
                .map(ClientMixinTestService::toUrl)
                .toArray(URL[]::new);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, true);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream input = getResourceAsStream(resource)) {
            if (input == null) {
                throw new ClassNotFoundException(name);
            }
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, readerFlags);
            return node;
        }
    }

    @Override
    public Collection<ITransformer> getTransformers() {
        return List.of();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return List.of();
    }

    @Override
    public void addTransformerExclusion(String name) {
    }

    @Override
    public void registerInvalidClass(String className) {
        invalidClasses.add(className);
    }

    @Override
    public boolean isClassLoaded(String className) {
        return false;
    }

    @Override
    public String getClassRestrictions(String className) {
        return invalidClasses.contains(className) ? "Invalid" : "";
    }

    @Override
    public void onApply(String className, String mixinName) {
    }

    @Override
    public void onPostProcess(String className) {
    }

    @Override
    public void onGenerate(String className, String generatorName) {
    }

    private static ClassLoader loader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
