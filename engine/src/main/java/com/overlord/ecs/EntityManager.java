package com.overlord.ecs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityManager {
    
    private final Map<Integer, Entity> entities;
    private final Map<Integer, Map<Class<? extends Component>, Component>> entityComponents;
    private final AtomicInteger nextEntityId;
    private final ComponentPool componentPool;
    
    public EntityManager() {
        entities = new HashMap<>();
        entityComponents = new HashMap<>();
        nextEntityId = new AtomicInteger(0);
        componentPool = new ComponentPool();
    }
    
    public Entity createEntity() {
        int id = nextEntityId.getAndIncrement();
        Entity entity = new Entity(id);
        entities.put(id, entity);
        entityComponents.put(id, new HashMap<>());
        return entity;
    }
    
    public void destroyEntity(Entity entity) {
        int id = entity.getId();
        entity.setActive(false);
        
        Map<Class<? extends Component>, Component> components = entityComponents.get(id);
        if (components != null) {
            for (Component component : components.values()) {
                releaseComponent(component);
            }
            components.clear();
        }
        
        entities.remove(id);
        entityComponents.remove(id);
    }
    
    public <T extends Component> void addComponent(Entity entity, T component) {
        int id = entity.getId();
        Map<Class<? extends Component>, Component> components = entityComponents.get(id);
        if (components == null) {
            components = new HashMap<>();
            entityComponents.put(id, components);
        }
        
        component.setEntity(entity);
        components.put(component.getComponentType(), component);
    }
    
    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponent(Entity entity, Class<T> componentType) {
        int id = entity.getId();
        Map<Class<? extends Component>, Component> components = entityComponents.get(id);
        if (components == null) return null;
        
        return (T) components.get(componentType);
    }
    
    public <T extends Component> boolean hasComponent(Entity entity, Class<T> componentType) {
        int id = entity.getId();
        Map<Class<? extends Component>, Component> components = entityComponents.get(id);
        if (components == null) return false;
        
        return components.containsKey(componentType);
    }
    
    public <T extends Component> void removeComponent(Entity entity, Class<T> componentType) {
        int id = entity.getId();
        Map<Class<? extends Component>, Component> components = entityComponents.get(id);
        if (components != null) {
            Component component = components.remove(componentType);
            if (component != null) {
                releaseComponent(component);
            }
        }
    }
    
    public Entity getEntity(int id) {
        return entities.get(id);
    }
    
    public boolean isEntityActive(Entity entity) {
        return entity != null && entity.isActive() && entities.containsKey(entity.getId());
    }
    
    public Set<Entity> getAllEntities() {
        return new HashSet<>(entities.values());
    }
    
    public int getEntityCount() {
        return entities.size();
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Component> void releaseComponent(T component) {
        Class<T> componentType = (Class<T>) component.getComponentType();
        componentPool.release(componentType, component);
    }
    
    public ComponentPool getComponentPool() {
        return componentPool;
    }
}