package com.overlord.ecs;

public class Entity {
    
    private final int id;
    private boolean active;
    
    public Entity(int id) {
        this.id = id;
        this.active = true;
    }
    
    public int getId() {
        return id;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Entity)) return false;
        Entity other = (Entity) obj;
        return this.id == other.id;
    }
    
    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
    
    @Override
    public String toString() {
        return "Entity{" + id + "}";
    }
}