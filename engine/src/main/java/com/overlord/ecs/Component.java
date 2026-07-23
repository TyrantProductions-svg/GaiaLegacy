package com.overlord.ecs;

public abstract class Component {
    
    private Entity entity;
    
    public Entity getEntity() {
        return entity;
    }
    
    public void setEntity(Entity entity) {
        this.entity = entity;
    }
    
    public abstract Class<? extends Component> getComponentType();
}