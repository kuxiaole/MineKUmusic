package me.sorihiko.dango.event;

public abstract class CancellableEvent implements Event {
    protected boolean cancelled = false;

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}