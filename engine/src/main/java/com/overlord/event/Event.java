package com.overlord.event;

public abstract class Event {
    
    private boolean cancelled = false;
    
    public boolean isCancelled() {
        return cancelled;
    }
    
    public void cancel() {
        this.cancelled = true;
    }
    
    public abstract String getEventType();
}