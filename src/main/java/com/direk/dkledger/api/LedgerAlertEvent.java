package com.direk.dkledger.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread for every new alert, after it's saved. A Discord bot listens to this to post alerts.
 */
public final class LedgerAlertEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AlertView alert;

    public LedgerAlertEvent(AlertView alert) {
        this.alert = alert;
    }

    public AlertView getAlert() {
        return alert;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
