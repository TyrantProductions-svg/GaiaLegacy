package com.overlord.ecs;

public abstract class System {
    
    private boolean enabled = true;
    
    public abstract void update(float deltaTime);
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void init() {
    }
    
    public void shutdown() {
    }
}