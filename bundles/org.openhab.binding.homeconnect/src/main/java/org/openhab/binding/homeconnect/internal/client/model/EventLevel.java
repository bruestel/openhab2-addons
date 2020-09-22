package org.openhab.binding.homeconnect.internal.client.model;

import org.eclipse.jdt.annotation.Nullable;

public enum EventLevel {
    CRITICAL("critical"),
    ALERT("alert"),
    WARNING("warning"),
    HINT("hint"),
    INFO("info");

    private final String level;

    EventLevel(String level) {
        this.level = level;
    }

    public String getLevel() {
        return this.level;
    }

    public static @Nullable EventLevel valueOfLevel(String type) {
        for (EventLevel eventType : EventLevel.values()) {
            if (eventType.level.equalsIgnoreCase(type)) {
                return eventType;
            }
        }
        return null;
    }
}
