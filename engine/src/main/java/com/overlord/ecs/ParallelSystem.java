package com.overlord.ecs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;

public abstract class ParallelSystem extends System {
    
    private final ExecutorService executor;
    private final int threadCount;
    
    public ParallelSystem() {
        this(Math.min(4, Runtime.getRuntime().availableProcessors()));
    }
    
    public ParallelSystem(int threadCount) {
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "ParallelSystem-Thread");
            t.setDaemon(true);
            return t;
        });
    }
    
    public abstract void processEntities(List<Entity> entities, float deltaTime);
    
    @Override
    public void update(float deltaTime) {
        List<Entity> entities = getRelevantEntities();
        if (entities.isEmpty()) return;
        
        int chunkSize = Math.max(1, entities.size() / threadCount);
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < entities.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, entities.size());
            List<Entity> chunk = entities.subList(i, end);
            
            futures.add(executor.submit(() -> processEntities(chunk, deltaTime)));
        }
        
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    public abstract List<Entity> getRelevantEntities();
    
    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
    
    public int getThreadCount() {
        return threadCount;
    }
}