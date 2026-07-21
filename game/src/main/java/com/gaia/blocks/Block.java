package com.gaia.blocks;

public class Block {
    
    private final BlockProperties properties;
    
    public Block(BlockProperties properties) {
        this.properties = properties;
    }
    
    public byte getId() {
        return properties.getId();
    }
    
    public String getName() {
        return properties.getName();
    }
    
    public float getTolerance() {
        return properties.getTolerance();
    }
    
    public float getStructuralIntegrity() {
        return properties.getStructuralIntegrity();
    }
    
    public float getHardness() {
        return properties.getHardness();
    }
    
    public boolean isTransparent() {
        return properties.isTransparent();
    }
    
    public int getLightLevel() {
        return properties.getLightLevel();
    }
    
    public boolean isFlammable() {
        return properties.isFlammable();
    }
    
    public boolean hasGravity() {
        return properties.hasGravity();
    }
    
    public float getBlastResistance() {
        return properties.getBlastResistance();
    }
    
    public BlockProperties getProperties() {
        return properties;
    }
    
    @Override
    public String toString() {
        return "Block{name='" + properties.getName() + "', tolerance=" + properties.getTolerance() + ", integrity=" + properties.getStructuralIntegrity() + "}";
    }
}