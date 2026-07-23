package com.overlord.core;

import java.util.HashMap;
import java.util.Map;

public class ServiceLocator {
    
    private static final ServiceLocator INSTANCE = new ServiceLocator();
    private final Map<Class<?>, Object> services = new HashMap<>();
    
    private ServiceLocator() {}
    
    public static ServiceLocator getInstance() {
        return INSTANCE;
    }
    
    public <T> void register(Class<T> serviceType, T service) {
        services.put(serviceType, service);
    }
    
    public <T> T get(Class<T> serviceType) {
        Object service = services.get(serviceType);
        if (service == null) {
            throw new IllegalStateException("Service not registered: " + serviceType.getName());
        }
        return serviceType.cast(service);
    }
    
    public <T> boolean has(Class<T> serviceType) {
        return services.containsKey(serviceType);
    }
    
    public void unregister(Class<?> serviceType) {
        services.remove(serviceType);
    }
    
    public void clear() {
        services.clear();
    }
}