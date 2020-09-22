package org.openhab.binding.homeconnect.internal.client.model;

import org.eclipse.jdt.annotation.Nullable;

public enum EventType {
    KEEP_ALIVE("KEEP-ALIVE"),
    STATUS("STATUS"),
    EVENT("EVENT"),
    NOTIFY("NOTIFY"),
    DISCONNECTED("DISCONNECTED"),
    CONNECTED("CONNECTED"),
    PAIRED("PAIRED"),
    DEPAIRED("DEPAIRED");

    private final String type;

    EventType(String type) {
        this.type = type;
    }

    public String getType() {
        return this.type;
    }

    public static @Nullable EventType valueOfType(@Nullable String type) {
        for (EventType eventType : EventType.values()) {
            if (eventType.type.equalsIgnoreCase(type)) {
                return eventType;
            }
        }
        return null;
    }
}
