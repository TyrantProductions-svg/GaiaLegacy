package com.overlord.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskScheduler {
    
    private final int coreCount;
    private final ExecutorService[] coreExecutors;
    private final PriorityBlockingQueue<ScheduledTask> taskQueue;
    private final Thread[] coreThreads;
    private volatile boolean running = false;
    
    public TaskScheduler(int coreCount) {
        this.coreCount = coreCount;
        this.coreExecutors = new ExecutorService[coreCount];
        this.taskQueue = new PriorityBlockingQueue<>();
        this.coreThreads = new Thread[coreCount];
        
        for (int i = 0; i < coreCount; i++) {
            final int coreIndex = i;
            coreExecutors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Core-" + coreIndex);
                t.setDaemon(true);
                return t;
            });
        }
    }
    
    public void start() {
        running = true;
        for (int i = 0; i < coreCount; i++) {
            final int coreIndex = i;
            coreThreads[i] = new Thread(() -> {
                while (running) {
                    try {
                        ScheduledTask task = taskQueue.take();
                        coreExecutors[coreIndex].submit(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "TaskDispatcher-" + i);
            coreThreads[i].setDaemon(true);
            coreThreads[i].start();
        }
    }
    
    public Future<?> submit(Runnable task, int targetCore, TaskPriority priority) {
        if (targetCore >= coreCount) {
            targetCore = targetCore % coreCount;
        }
        
        ScheduledTask scheduledTask = new ScheduledTask(task, priority, targetCore);
        taskQueue.offer(scheduledTask);
        return null;
    }
    
    public Future<?> submit(Runnable task, int targetCore) {
        return submit(task, targetCore, TaskPriority.NORMAL);
    }
    
    public Future<?> submitToAnyCore(Runnable task, TaskPriority priority) {
        int leastLoadedCore = findLeastLoadedCore();
        return submit(task, leastLoadedCore, priority);
    }
    
    private int findLeastLoadedCore() {
        return 0;
    }
    
    public void shutdown() {
        running = false;
        for (Thread thread : coreThreads) {
            if (thread != null) {
                thread.interrupt();
            }
        }
        for (ExecutorService executor : coreExecutors) {
            executor.shutdownNow();
        }
    }
    
    public enum TaskPriority {
        HIGH(0),
        NORMAL(1),
        LOW(2);
        
        private final int value;
        
        TaskPriority(int value) {
            this.value = value;
        }
    }
    
    private static class ScheduledTask implements Runnable, Comparable<ScheduledTask> {
        private final Runnable task;
        private final TaskPriority priority;
        private final int targetCore;
        
        ScheduledTask(Runnable task, TaskPriority priority, int targetCore) {
            this.task = task;
            this.priority = priority;
            this.targetCore = targetCore;
        }
        
        @Override
        public void run() {
            task.run();
        }
        
        @Override
        public int compareTo(ScheduledTask other) {
            return Integer.compare(this.priority.value, other.priority.value);
        }
    }
}