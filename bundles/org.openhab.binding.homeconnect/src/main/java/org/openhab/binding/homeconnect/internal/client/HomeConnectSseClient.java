/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homeconnect.internal.client;

import static java.net.HttpURLConnection.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;
import static org.openhab.binding.homeconnect.internal.client.OkHttpHelper.formatJsonBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.here.oksse.OkSse;
import com.here.oksse.ServerSentEvent;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Server-Sent-Events client for Home Connect API.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class HomeConnectSseClient {

    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String KEEP_ALIVE = "KEEP-ALIVE";
    private static final String EMPTY_EVENT = "\"\"";
    private static final String DISCONNECTED = "DISCONNECTED";
    private static final String CONNECTED = "CONNECTED";
    private static final int SSE_REQUEST_READ_TIMEOUT = 90;
    private static final String ACCEPT = "Accept";

    private final Logger logger = LoggerFactory.getLogger(HomeConnectSseClient.class);
    private final String apiUrl;
    private final OkSse oksse;
    private final OAuthClientService oAuthClientService;
    private final HashSet<ServerSentEventListener> eventListeners;
    private final HashMap<ServerSentEventListener, ServerSentEvent> serverSentEventConnections;

    public HomeConnectSseClient(OAuthClientService oAuthClientService, boolean simulated) {
        apiUrl = simulated ? API_SIMULATOR_BASE_URL : API_BASE_URL;
        oksse = new OkSse(OkHttpHelper.builder().readTimeout(SSE_REQUEST_READ_TIMEOUT, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build());
        this.oAuthClientService = oAuthClientService;

        eventListeners = new HashSet<>();
        serverSentEventConnections = new HashMap<>();
    }

    /**
     * Register {@link ServerSentEventListener} to receive SSE events by Home Conncet API. This helps to reduce the
     * amount of request you would usually need to update all channels.
     *
     * Checkout rate limits of the API at. https://developer.home-connect.com/docs/general/ratelimiting
     *
     * @param eventListener
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public synchronized void registerServerSentEventListener(final String haId,
            final ServerSentEventListener eventListener) throws CommunicationException, AuthorizationException {

        logger.debug("Register event listener: {}", eventListener);
        eventListeners.add(eventListener);

        if (!serverSentEventConnections.containsKey(eventListener)) {
            Request request = OkHttpHelper.requestBuilder(oAuthClientService)
                    .url(apiUrl + "/api/homeappliances/" + haId + "/events").header(ACCEPT, TEXT_EVENT_STREAM).build();

            logger.debug("Create new SSE listener");
            ServerSentEvent sse = oksse.newServerSentEvent(request, new ServerSentEvent.Listener() {

                @Override
                public void onOpen(ServerSentEvent sse, Response response) {
                    logger.debug("[{}] SSE channel opened", haId);
                }

                @Override
                public void onMessage(ServerSentEvent sse, String id, String event, String message) {
                    if (logger.isDebugEnabled()) {
                        if (KEEP_ALIVE.equals(event)) {
                            logger.debug("[{}] SSE KEEP-ALIVE", haId);
                        } else {
                            logger.debug("[{}] SSE received id: {}, event: {}, message:\n{}", haId, id, event,
                                    formatJsonBody(message));
                        }
                    }

                    if (!StringUtils.isEmpty(message) && !EMPTY_EVENT.equals(message)) {
                        ArrayList<Event> events = mapToEvents(message);
                        events.forEach(e -> eventListener.onEvent(e));
                    }

                    if (CONNECTED.equals(event) || DISCONNECTED.equals(event)) {
                        eventListener.onEvent(new Event(event, null, null));
                    }
                }

                @Override
                public void onComment(ServerSentEvent sse, String comment) {
                    logger.debug("[{}] SSE comment received comment: {}", haId, comment);
                }

                @Override
                public boolean onRetryTime(ServerSentEvent sse, long milliseconds) {
                    logger.debug("[{}] SSE retry time {}", haId, milliseconds);
                    return true; // True to use the new retry time received by SSE
                }

                @Override
                public boolean onRetryError(ServerSentEvent sse, Throwable throwable, Response response) {
                    boolean retry = true;

                    if (logger.isWarnEnabled() && throwable != null) {
                        logger.warn("[{}] SSE error: {}", haId, throwable.getMessage());
                    }

                    if (response != null) {
                        if (response.code() == HTTP_FORBIDDEN) {
                            logger.warn(
                                    "[{}] Stopping SSE listener! Got FORBIDDEN response from server. Please check if you allowed to access this device.",
                                    haId);
                            retry = false;
                        } else if (response.code() == HTTP_UNAUTHORIZED) {
                            logger.error("[{}] Stopping SSE listener! Access token became invalid.", haId);
                            retry = false;
                        }

                        response.close();
                    }

                    if (!retry) {
                        eventListener.onReconnectFailed();

                        serverSentEventConnections.remove(eventListener);
                        eventListeners.remove(eventListener);
                    }

                    return retry;
                }

                @Override
                public void onClosed(ServerSentEvent sse) {
                    logger.debug("[{}] SSE closed", haId);
                }

                @Override
                public Request onPreRetry(ServerSentEvent sse, Request request) {
                    eventListener.onReconnect();

                    try {
                        return OkHttpHelper.requestBuilder(oAuthClientService)
                                .url(apiUrl + "/api/homeappliances/" + haId + "/events")
                                .header(ACCEPT, TEXT_EVENT_STREAM).build();
                    } catch (AuthorizationException | CommunicationException e) {
                        logger.debug("Could not create new SSE request. {}", e.getMessage());
                    }

                    return request;
                }
            });
            serverSentEventConnections.put(eventListener, sse);
        }
    }

    /**
     * Unregister {@link ServerSentEventListener}.
     *
     * @param eventListener
     */
    public synchronized void unregisterServerSentEventListener(ServerSentEventListener eventListener) {
        eventListeners.remove(eventListener);

        if (serverSentEventConnections.containsKey(eventListener)) {
            serverSentEventConnections.get(eventListener).close();
            serverSentEventConnections.remove(eventListener);
        }
    }

    public synchronized void dispose() {
        eventListeners.clear();

        serverSentEventConnections.forEach((key, value) -> value.close());
        serverSentEventConnections.clear();
    }

    private ArrayList<Event> mapToEvents(String json) {
        ArrayList<Event> events = new ArrayList<>();

        try {
            JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();
            JsonArray items = responseObject.getAsJsonArray("items");

            items.forEach(item -> {
                JsonObject obj = (JsonObject) item;
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                String value = obj.get("value") != null && !obj.get("value").isJsonNull()
                        ? obj.get("value").getAsString()
                        : null;
                String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

                events.add(new Event(key, value, unit));
            });

        } catch (IllegalStateException e) {
            logger.error("Could not parse event! {}", e.getMessage());
        }
        return events;
    }
}
