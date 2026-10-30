package io.github.dirtyredz.danktransfer.mixincheck;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated global property storage for the headless Mixin test service. */
public final class ClientMixinTestProperties implements IGlobalPropertyService {
    private final Map<IPropertyKey, Object> values = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(IPropertyKey key) {
        return (T) values.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) values.getOrDefault(key, defaultValue);
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private record Key(String name) implements IPropertyKey {
    }
}
