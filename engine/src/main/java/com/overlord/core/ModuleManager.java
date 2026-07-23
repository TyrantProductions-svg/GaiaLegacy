package com.overlord.core;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {
    
    private static final ModuleManager INSTANCE = new ModuleManager();
    private final List<GameModule> modules = new ArrayList<>();
    
    private ModuleManager() {}
    
    public static ModuleManager getInstance() {
        return INSTANCE;
    }
    
    public void register(GameModule module) {
        modules.add(module);
    }
    
    public void unregister(GameModule module) {
        module.shutdown();
        modules.remove(module);
    }
    
    public void initAll() {
        for (GameModule module : modules) {
            module.initialize();
        }
    }
    
    public void updateAll(float deltaTime) {
        for (GameModule module : modules) {
            module.update(deltaTime);
        }
    }
    
    public void shutdownAll() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            modules.get(i).shutdown();
        }
    }
    
    public interface GameModule {
        void initialize();
        void update(float deltaTime);
        void shutdown();
    }
}