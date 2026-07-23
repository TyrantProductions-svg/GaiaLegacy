package com.overlord.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

public class TaskScheduler {
    
    private final int coreCount;
    private final ExecutorService[] coreExecutors;
    private final PriorityBlockingQueue<ScheduledTask> taskQueue;
    private final Thread[] dispatcherThreads;
    private volatile boolean running = false;
    
    public TaskScheduler(int coreCount) {
        this.coreCount = coreCount;
        this.coreExecutors = new ExecutorService[coreCount];
        this.taskQueue = new PriorityBlockingQueue<>();
        this.dispatcherThreads = new Thread[coreCount];
        
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
            dispatcherThreads[i] = new Thread(() -> {
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
            dispatcherThreads[i].setDaemon(true);
            dispatcherThreads[i].start();
        }
    }
    
    public void submit(Runnable task, int targetCore, TaskPriority priority) {
        if (targetCore >= coreCount) {
            targetCore = targetCore % coreCount;
        }
        taskQueue.offer(new ScheduledTask(task, priority));
    }
    
    public void submit(Runnable task, int targetCore) {
        submit(task, targetCore, TaskPriority.NORMAL);
    }
    
    public void shutdown() {
        running = false;
        for (Thread thread : dispatcherThreads) {
            if (thread != null) thread.interrupt();
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
        
        ScheduledTask(Runnable task, TaskPriority priority) {
            this.task = task;
            this.priority = priority;
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