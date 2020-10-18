package org.openhab.binding.homeconnect.internal.client;

import static java.time.LocalDateTime.now;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.openhab.binding.homeconnect.internal.client.model.EventType.EVENT;
import static org.openhab.binding.homeconnect.internal.client.model.EventType.NOTIFY;
import static org.openhab.binding.homeconnect.internal.client.model.EventType.STATUS;
import static org.openhab.binding.homeconnect.internal.client.model.EventType.valueOfType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homeconnect.internal.client.listener.HomeConnectEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.EventHandling;
import org.openhab.binding.homeconnect.internal.client.model.EventLevel;
import org.openhab.binding.homeconnect.internal.client.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

@NonNullByDefault
public class HomeConnectEventSourceListener extends EventSourceListener {
    private static final String EMPTY_DATA = "\"\"";
    private static final int SSE_MONITOR_INITIAL_DELAY = 1;
    private static final int SSE_MONITOR_INTERVAL = 5; // in min
    private static final int SSE_MONITOR_BROKEN_CONNECTION_TIMEOUT = 3; // in min

    private final String haId;
    private final HomeConnectEventListener eventListener;
    private final HomeConnectEventSourceClient client;
    private final Logger logger;
    private final JsonParser jsonParser;
    private final ScheduledFuture<?> eventSourceMonitorFuture;
    private final Queue<Event> eventQueue;

    private @Nullable LocalDateTime lastEventReceived;

    public HomeConnectEventSourceListener(String haId, final HomeConnectEventListener eventListener,
            final HomeConnectEventSourceClient client, final ScheduledExecutorService scheduler,
            Queue<Event> eventQueue) {
        this.haId = haId;
        this.eventListener = eventListener;
        this.client = client;
        this.eventQueue = eventQueue;
        jsonParser = new JsonParser();
        logger = LoggerFactory.getLogger(HomeConnectEventSourceListener.class);

        eventSourceMonitorFuture = createMonitor(scheduler);
    }

    @Override
    public void onOpen(@Nullable EventSource eventSource, @Nullable Response response) {
        logger.debug("Event source listener channel opened ({}).", haId);
    }

    @Override
    public void onEvent(@Nullable EventSource eventSource, @Nullable String id, @Nullable String type,
            @Nullable String data) {
        lastEventReceived = now();

        @Nullable
        EventType eventType = valueOfType(type);
        if (eventType != null) {
            mapEventSourceEventToEvent(haId, eventType, data).forEach(event -> {
                eventQueue.add(event);
                logger.debug("Received event ({}): {}", haId, event);
                try {
                    eventListener.onEvent(event);
                } catch (Exception e) {
                    logger.error("Could not publish event to Listener!", e);
                }
            });
        } else {
            logger.warn("Received unknown event source type! haId={}, id={}, type={}, data={}", haId, id, type, data);
        }
    }

    @Override
    public void onClosed(@Nullable EventSource eventSource) {
        logger.debug("Event source listener channel closed ({}).", haId);

        client.unregisterEventListener(eventListener);
        stopMonitor();

        try {
            eventListener.onClosed();
        } catch (Exception e) {
            logger.error("Could not publish closed event to listener ({})!", haId, e);
        }
    }

    @Override
    public void onFailure(@Nullable EventSource eventSource, @Nullable Throwable throwable,
            @Nullable Response response) {
        @Nullable
        String throwableMessage = throwable != null ? throwable.getMessage() : null;
        @Nullable
        String throwableClass = throwable != null ? throwable.getClass().getName() : null;
        @Nullable
        String responseCode = response != null ? String.valueOf(response.code()) : null;

        logger.debug(haId,
                "Event source listener connection failure occurred. haId={}, responseCode={}, throwable={}, throwableMessage={}",
                haId, responseCode, throwableClass, throwableMessage);

        if (response != null) {
            response.close();
        }

        client.unregisterEventListener(eventListener);
        stopMonitor();

        try {
            eventListener.onClosed();
        } catch (Exception e) {
            logger.error("Could not publish closed event to listener ({})!", haId, e);
        }
    }

    private ScheduledFuture<?> createMonitor(ScheduledExecutorService scheduler) {
        return scheduler.scheduleWithFixedDelay(() -> {
            logger.trace("Check event source connection ({}). Last event package received at {}.", haId,
                    lastEventReceived);
            if (lastEventReceived != null
                    && ChronoUnit.MINUTES.between(lastEventReceived, now()) > SSE_MONITOR_BROKEN_CONNECTION_TIMEOUT) {
                logger.warn("Dead event source connection detected ({}).", haId);

                client.unregisterEventListener(eventListener);
                stopMonitor();

                try {
                    eventListener.onClosed();
                } catch (Exception e) {
                    logger.error("Could not publish closed event to listener ({})!", haId, e);
                }

            }
        }, SSE_MONITOR_INITIAL_DELAY, SSE_MONITOR_INTERVAL, TimeUnit.MINUTES);
    }

    private void stopMonitor() {
        logger.debug("Dispose event source connection monitor of appliance ({}).", haId);
        eventSourceMonitorFuture.cancel(true);
    }

    private List<Event> mapEventSourceEventToEvent(String haId, EventType type, @Nullable String data) {
        List<Event> events = new ArrayList<>();

        if ((STATUS.equals(type) || EVENT.equals(type) || NOTIFY.equals(type)) && data != null && !isEmpty(data)
                && !EMPTY_DATA.equals(data)) {
            try {
                JsonObject responseObject = jsonParser.parse(data).getAsJsonObject();
                JsonArray items = responseObject.getAsJsonArray("items");

                items.forEach(item -> {
                    JsonObject obj = (JsonObject) item;
                    @Nullable
                    String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                    @Nullable
                    String value = obj.get("value") != null && !obj.get("value").isJsonNull()
                            ? obj.get("value").getAsString()
                            : null;
                    @Nullable
                    String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;
                    @Nullable
                    String name = obj.get("name") != null ? obj.get("name").getAsString() : null;
                    @Nullable
                    String uri = obj.get("uri") != null ? obj.get("uri").getAsString() : null;
                    @Nullable
                    EventLevel level = obj.get("level") != null
                            ? EventLevel.valueOfLevel(obj.get("level").getAsString())
                            : null;
                    @Nullable
                    EventHandling handling = obj.get("handling") != null
                            ? EventHandling.valueOfHandling(obj.get("handling").getAsString())
                            : null;
                    @Nullable
                    Long timestamp = obj.get("timestamp") != null ? obj.get("timestamp").getAsLong() : null;
                    @Nullable
                    LocalDateTime creation = timestamp != null
                            ? LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp),
                                    TimeZone.getDefault().toZoneId())
                            : null;

                    events.add(new Event(haId, type, key, name, uri, creation, level, handling, value, unit));
                });
            } catch (IllegalStateException e) {
                logger.error("Could not parse event! haId={}, error={}", haId, e.getMessage());
            }
        } else {
            events.add(new Event(haId, type));
        }

        return events;
    }
}
