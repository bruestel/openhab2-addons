package org.openhab.binding.homeconnect.internal.client.model;

import org.eclipse.jdt.annotation.Nullable;

public enum EventHandling {
    NONE("none"),
    ACKNOWLEDGE("acknowledge"),
    DECISION("decision");

    private final String handling;

    EventHandling(String handling) {
        this.handling = handling;
    }

    public String getHandling() {
        return this.handling;
    }

    public static @Nullable EventHandling valueOfHandling(String type) {
        for (EventHandling eventType : EventHandling.values()) {
            if (eventType.handling.equalsIgnoreCase(type)) {
                return eventType;
            }
        }
        return null;
    }
}
