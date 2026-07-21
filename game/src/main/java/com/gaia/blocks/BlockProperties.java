package com.gaia.blocks;

public class BlockProperties {
    
    private final byte id;
    private final String name;
    private final float tolerance;
    private final float structuralIntegrity;
    private final float hardness;
    private final boolean transparent;
    private final int lightLevel;
    private final boolean flammable;
    private final boolean gravity;
    private final float blastResistance;
    
    private BlockProperties(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.tolerance = builder.tolerance;
        this.structuralIntegrity = builder.structuralIntegrity;
        this.hardness = builder.hardness;
        this.transparent = builder.transparent;
        this.lightLevel = builder.lightLevel;
        this.flammable = builder.flammable;
        this.gravity = builder.gravity;
        this.blastResistance = builder.blastResistance;
    }
    
    public byte getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public float getTolerance() {
        return tolerance;
    }
    
    public float getStructuralIntegrity() {
        return structuralIntegrity;
    }
    
    public float getHardness() {
        return hardness;
    }
    
    public boolean isTransparent() {
        return transparent;
    }
    
    public int getLightLevel() {
        return lightLevel;
    }
    
    public boolean isFlammable() {
        return flammable;
    }
    
    public boolean hasGravity() {
        return gravity;
    }
    
    public float getBlastResistance() {
        return blastResistance;
    }
    
    @Override
    public String toString() {
        return "BlockProperties{id=" + id + ", name='" + name + "', tolerance=" + tolerance + ", integrity=" + structuralIntegrity + "}";
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private byte id = 0;
        private String name = "unknown";
        private float tolerance = 1.0f;
        private float structuralIntegrity = 50.0f;
        private float hardness = 1.0f;
        private boolean transparent = false;
        private int lightLevel = 0;
        private boolean flammable = false;
        private boolean gravity = false;
        private float blastResistance = 1.0f;
        
        public Builder id(byte id) {
            this.id = id;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder tolerance(float tolerance) {
            this.tolerance = Math.max(1.0f, Math.min(10.0f, tolerance));
            return this;
        }
        
        public Builder structuralIntegrity(float structuralIntegrity) {
            this.structuralIntegrity = Math.max(0.0f, Math.min(100.0f, structuralIntegrity));
            return this;
        }
        
        public Builder hardness(float hardness) {
            this.hardness = Math.max(0.1f, hardness);
            return this;
        }
        
        public Builder transparent(boolean transparent) {
            this.transparent = transparent;
            return this;
        }
        
        public Builder lightLevel(int lightLevel) {
            this.lightLevel = Math.max(0, Math.min(15, lightLevel));
            return this;
        }
        
        public Builder flammable(boolean flammable) {
            this.flammable = flammable;
            return this;
        }
        
        public Builder gravity(boolean gravity) {
            this.gravity = gravity;
            return this;
        }
        
        public Builder blastResistance(float blastResistance) {
            this.blastResistance = Math.max(0.0f, blastResistance);
            return this;
        }
        
        public BlockProperties build() {
            return new BlockProperties(this);
        }
    }
}