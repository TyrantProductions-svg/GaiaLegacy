package com.overlord.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class EventBus {
    
    private static final EventBus INSTANCE = new EventBus();
    
    private final Map<Class<? extends Event>, List<EventHandler<? extends Event>>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Event> eventQueue = new ConcurrentLinkedQueue<>();
    
    private EventBus() {}
    
    public static EventBus getInstance() {
        return INSTANCE;
    }
    
    public <T extends Event> void subscribe(Class<T> eventType, EventHandler<T> handler) {
        handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
    }
    
    public <T extends Event> void unsubscribe(Class<T> eventType, EventHandler<T> handler) {
        List<EventHandler<? extends Event>> handlerList = handlers.get(eventType);
        if (handlerList != null) {
            handlerList.remove(handler);
        }
    }
    
    public void publish(Event event) {
        eventQueue.offer(event);
    }
    
    @SuppressWarnings("unchecked")
    public void processAll() {
        Event event;
        while ((event = eventQueue.poll()) != null) {
            if (event.isCancelled()) continue;
            
            List<EventHandler<? extends Event>> eventHandlers = handlers.get(event.getClass());
            if (eventHandlers != null) {
                for (EventHandler<? extends Event> handler : eventHandlers) {
                    if (event.isCancelled()) break;
                    ((EventHandler<Event>) handler).handle(event);
                }
            }
        }
    }
    
    public void clear() {
        handlers.clear();
        eventQueue.clear();
    }
}