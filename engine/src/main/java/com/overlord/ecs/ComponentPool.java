package com.overlord.ecs;

import java.util.HashMap;
import java.util.Map;

public class ComponentPool {
    
    private final Map<Class<? extends Component>, Component[]> pools;
    private final Map<Class<? extends Component>, Integer> poolSizes;
    private static final int DEFAULT_POOL_SIZE = 100;
    
    public ComponentPool() {
        pools = new HashMap<>();
        poolSizes = new HashMap<>();
    }
    
    @SuppressWarnings("unchecked")
    public <T extends Component> T acquire(Class<T> componentType) {
        Component[] pool = pools.get(componentType);
        Integer size = poolSizes.getOrDefault(componentType, 0);
        
        if (pool == null || size >= pool.length) {
            expandPool(componentType);
            pool = pools.get(componentType);
        }
        
        T component = (T) pool[size];
        poolSizes.put(componentType, size + 1);
        return component;
    }
    
    public <T extends Component> void release(Class<T> componentType, T component) {
        Integer size = poolSizes.getOrDefault(componentType, 0);
        if (size > 0) {
            Component[] pool = pools.get(componentType);
            pool[size - 1] = component;
            poolSizes.put(componentType, size - 1);
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Component> void expandPool(Class<T> componentType) {
        try {
            int newSize = DEFAULT_POOL_SIZE;
            Component[] newPool = (Component[]) java.lang.reflect.Array.newInstance(componentType, newSize);
            
            for (int i = 0; i < newSize; i++) {
                newPool[i] = componentType.getDeclaredConstructor().newInstance();
            }
            
            pools.put(componentType, newPool);
            poolSizes.put(componentType, 0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create component pool for: " + componentType.getName(), e);
        }
    }
}