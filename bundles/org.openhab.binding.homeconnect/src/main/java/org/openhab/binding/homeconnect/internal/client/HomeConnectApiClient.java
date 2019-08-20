/**
 * Copyright (c) 2018-2019 Contributors to the openHAB project
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
import static org.apache.commons.lang.StringUtils.isEmpty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.InvalidTokenException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgram;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgramOption;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.here.oksse.OkSse;
import com.here.oksse.ServerSentEvent;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for Home Connect API.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class HomeConnectApiClient {
    private final static String API_URL = "https://api.home-connect.com";
    private final static String API_SIMULATOR_URL = "https://simulator.home-connect.com";
    private final static String ACCEPT = "Accept";
    private final static String CONTENT_TYPE = "Content-Type";
    private final static String BSH_JSON_V1 = "application/vnd.bsh.sdk.v1+json";
    private final static String TEXT_EVENT_STREAM = "text/event-stream";
    private final static String KEEP_ALIVE = "KEEP-ALIVE";
    private final static String EMPTY_EVENT = "\"\"";
    private final static String DISCONNECTED = "DISCONNECTED";
    private final static String CONNECTED = "CONNECTED";
    private final static int SSE_REQUEST_READ_TIMEOUT = 90;
    private final static int REQUEST_READ_TIMEOUT = 30;

    private final Logger logger = LoggerFactory.getLogger(HomeConnectApiClient.class);
    private final OkHttpClient client;
    private final String apiUrl;

    private String accessToken;
    private Supplier<String> newAccessTokenFunction;

    private final Set<ServerSentEventListener> eventListeners;
    private final HashMap<String, ServerSentEvent> serverSentEvent;

    private final ConcurrentHashMap<String, List<AvailableProgramOption>> availableProgramOptionsCache;

    private OkSse oksse;

    public HomeConnectApiClient(String accessToken, boolean simulated, Supplier<String> newAccessTokenFunction) {
        this.accessToken = accessToken;
        this.newAccessTokenFunction = newAccessTokenFunction;

        availableProgramOptionsCache = new ConcurrentHashMap<String, List<AvailableProgramOption>>();

        eventListeners = ConcurrentHashMap.newKeySet();
        serverSentEvent = new HashMap<>();

        // setup http client
        client = new OkHttpClient.Builder().readTimeout(REQUEST_READ_TIMEOUT, TimeUnit.SECONDS).build();
        apiUrl = simulated ? API_SIMULATOR_URL : API_URL;

        // configure Server Sent Event client
        // if no keep-alive events arrive within 90 seconds --> fail and try to reconnect
        oksse = new OkSse(new OkHttpClient.Builder().readTimeout(SSE_REQUEST_READ_TIMEOUT, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build());
    }

    /**
     * Get all home appliances
     *
     * @return list of {@link HomeAppliance}
     * @throws CommunicationException
     */
    public synchronized List<HomeAppliance> getHomeAppliances() throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = createGetRequest("/api/homeappliances");

        try (Response response = client.newCall(request).execute()) {

            checkResponseCode(HTTP_OK, response);

            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getHomeAppliances()] Response code: {} \n{}", response.code(), toPrettyFormat(body));
            }

            return mapToHomeAppliances(body);

        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getHomeAppliances()] Retrying method.");
            return getHomeAppliances();
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    /**
     * Get home appliance by id
     *
     * @param haId home appliance id
     * @return {@link HomeAppliance}
     * @throws CommunicationException
     */
    public synchronized HomeAppliance getHomeAppliance(String haId) throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = createGetRequest("/api/homeappliances/" + haId);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getHomeAppliance({})] Response code: {},\n{}", haId, response.code(),
                        toPrettyFormat(body));
            }

            return mapToHomeAppliance(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getHomeAppliance({})] Retrying method.", haId);
            return getHomeAppliance(haId);
        }
    }

    /**
     * Get power state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getPowerState(String haId) throws CommunicationException {
        return getSetting(haId, "BSH.Common.Setting.PowerState");
    }

    /**
     * Set power state of device.
     *
     * @param haId home appliance id
     * @param state target state
     * @throws CommunicationException
     */
    public void setPowerState(String haId, String state) throws CommunicationException {
        putSettings(haId, new Data("BSH.Common.Setting.PowerState", state, null));
    }

    /**
     * Get setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getFreezerSetpointTemperature(String haId) throws CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer");
    }

    /**
     * Set setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException
     */
    public void setFreezerSetpointTemperature(String haId, String state, String unit) throws CommunicationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer", state, unit),
                true);
    }

    /**
     * Get setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     */
    public Data getFridgeSetpointTemperature(String haId) throws CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator");
    }

    /**
     * Set setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException
     */
    public void setFridgeSetpointTemperature(String haId, String state, String unit) throws CommunicationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator", state, unit),
                true);
    }

    /**
     * Get fridge super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getFridgeSuperMode(String haId) throws CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator");
    }

    /**
     * Get freezer super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getFreezerSuperMode(String haId) throws CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer");
    }

    /**
     * Get door state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getDoorState(String haId) throws CommunicationException {
        return getStatus(haId, "BSH.Common.Status.DoorState");
    }

    /**
     * Get operation state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     */
    public Data getOperationState(String haId) throws CommunicationException {
        return getStatus(haId, "BSH.Common.Status.OperationState");
    }

    /**
     * Is remote start allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     */
    public boolean isRemoteControlStartAllowed(String haId) throws CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlStartAllowed");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is remote control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     */
    public boolean isRemoteControlActive(String haId) throws CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is local control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     */
    public boolean isLocalControlActive(String haId) throws CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.LocalControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Get active program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no active program
     * @throws CommunicationException
     */
    public Program getActiveProgram(String haId) throws CommunicationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    /**
     * Get selected program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no selected program
     * @throws CommunicationException
     */
    public Program getSelectedProgram(String haId) throws CommunicationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/selected");
    }

    public void setSelectedProgram(String haId, String program) throws CommunicationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/selected", new Data(program, null, null), false);
    }

    public void startProgram(String haId, String program) throws CommunicationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/active", new Data(program, null, null), false);
    }

    public void setProgramOptions(String haId, String key, String value, String unit, boolean valueAsInt,
            boolean isProgramActive) throws CommunicationException {
        String programState = isProgramActive ? "active" : "selected";

        putOption(haId, "/api/homeappliances/" + haId + "/programs/" + programState + "/options",
                new Option(key, value, unit), valueAsInt);
    }

    public void stopProgram(String haId) throws CommunicationException {
        sendDelete(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    public List<AvailableProgram> getPrograms(String haId) throws CommunicationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs");
    }

    public List<AvailableProgram> getAvailablePrograms(String haId) throws CommunicationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs/available");
    }

    public synchronized List<AvailableProgramOption> getProgramOptions(String haId, String programKey)
            throws CommunicationException {

        if (availableProgramOptionsCache.containsKey(programKey)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Returning cached options for `{}` \n{}", programKey,
                        availableProgramOptionsCache.get(programKey));
            }
            return availableProgramOptionsCache.get(programKey);
        }

        String path = "/api/homeappliances/" + haId + "/programs/available/" + programKey;
        checkOrRefreshAccessToken();

        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgramOptions({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        toPrettyFormat(body));
            }

            List<AvailableProgramOption> availableProgramOptions = mapToAvailableProgramOption(body);
            availableProgramOptionsCache.put(programKey, availableProgramOptions);
            return availableProgramOptions;
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getProgramOptions({}, {})] Retrying method.", haId, path);
            return getProgramOptions(haId, path);
        }
    }

    /**
     * Register {@link ServerSentEventListener} to receive SSE events by Home Conncet API. This helps to reduce the
     * amount of request you would usually need to update all channels.
     *
     * Checkout rate limits of the API at. https://developer.home-connect.com/docs/general/ratelimiting
     *
     * @param eventListener
     * @throws CommunicationException
     */
    public synchronized void registerEventListener(ServerSentEventListener eventListener)
            throws CommunicationException {
        String haId = eventListener.haId();

        logger.debug("Register event listener: {}", eventListener);
        eventListeners.add(eventListener);

        if (!serverSentEvent.containsKey(haId)) {
            checkOrRefreshAccessToken();
            Request request = new Request.Builder().url(apiUrl + "/api/homeappliances/" + haId + "/events")
                    .header(ACCEPT, TEXT_EVENT_STREAM).addHeader("Authorization", "Bearer " + getAccessToken()).build();

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
                                    toPrettyFormat(message));
                        }
                    }

                    if (!isEmpty(message) && !EMPTY_EVENT.equals(message)) {
                        ArrayList<Event> events = mapToEvents(message);
                        events.forEach(e -> eventListeners.forEach(listener -> {
                            if (listener.haId().equalsIgnoreCase(haId)) {
                                listener.onEvent(e);
                            }
                        }));
                    }

                    if (CONNECTED.equals(event) || DISCONNECTED.equals(event)) {
                        eventListeners.forEach(listener -> {
                            if (listener.haId().equals(haId)) {
                                listener.onEvent(new Event(event, null, null));
                            }
                        });
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
                        eventListeners.forEach(listener -> {
                            if (listener.haId().equals(haId)) {
                                listener.onReconnectFailed();
                            }
                        });
                        serverSentEvent.remove(haId);
                        eventListeners.remove(eventListener);
                    }

                    return retry; // True to retry, false otherwise
                }

                @Override
                public void onClosed(ServerSentEvent sse) {
                    logger.debug("[{}] SSE closed", haId);
                }

                @Override
                public Request onPreRetry(ServerSentEvent sse, Request request) {
                    eventListeners.forEach(listener -> {
                        if (listener.haId().equals(haId)) {
                            listener.onReconnect();
                        }
                    });
                    return request;
                }
            });
            serverSentEvent.put(haId, sse);
        }
    }

    /**
     * Unregister {@link ServerSentEventListener}.
     *
     * @param eventListener
     */
    public synchronized void unregisterEventListener(ServerSentEventListener eventListener) {
        eventListeners.remove(eventListener);
        String haId = eventListener.haId();

        // remove unused SSE connections
        boolean needToRemoveSse = true;
        for (ServerSentEventListener el : eventListeners) {
            if (el.haId().equals(haId)) {
                needToRemoveSse = false;
            }
        }
        if (needToRemoveSse && serverSentEvent.containsKey(haId)) {
            serverSentEvent.get(haId).close();
            serverSentEvent.remove(haId);
        }
    }

    /**
     * Dispose and shutdown API client.
     */
    public synchronized void dispose() {
        eventListeners.clear();

        serverSentEvent.forEach((key, value) -> value.close());
        serverSentEvent.clear();
    }

    private Data getSetting(String haId, String setting) throws CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/settings/" + setting);
    }

    private void putSettings(String haId, Data data) throws CommunicationException {
        putSettings(haId, data, false);
    }

    private void putSettings(String haId, Data data, boolean asInt) throws CommunicationException {
        putData(haId, "/api/homeappliances/" + haId + "/settings/" + data.getName(), data, asInt);
    }

    private Data getStatus(String haId, String status) throws CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/status/" + status);
    }

    private synchronized Program getProgram(String haId, String path) throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK, HTTP_NOT_FOUND), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgram({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        toPrettyFormat(body));
            }

            if (response.code() == HTTP_OK) {
                return mapToProgram(body);
            }
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getProgram({}, {})] Retrying method.", haId, path);
            return getProgram(haId, path);
        }

        return null;
    }

    private synchronized List<AvailableProgram> getAvailablePrograms(String haId, String path)
            throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgram({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        toPrettyFormat(body));
            }

            return mapToAvailablePrograms(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getAvailablePrograms({}, {})] Retrying method.", haId, path);
            return getAvailablePrograms(haId, path);
        }
    }

    private synchronized void sendDelete(String haId, String path) throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).delete()
                .addHeader("Authorization", "Bearer " + getAccessToken()).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            if (logger.isDebugEnabled()) {
                logger.debug("[sendDelete({}, {})] Response code: {}", haId, path, response.code());
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[sendDelete({}, {})] Retrying method.", haId, path);
            sendDelete(haId, path);
        }
    }

    private synchronized Data getData(String haId, String path) throws CommunicationException {
        checkOrRefreshAccessToken();

        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getData({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        toPrettyFormat(body));
            }

            return mapToState(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[getData({}, {})] Retrying method.", haId, path);
            return getData(haId, path);
        }
    }

    private synchronized void putData(String haId, String path, Data data, boolean asInt)
            throws CommunicationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", data.getName());

        if (data.getValue() != null) {
            if (asInt) {
                innerObject.addProperty("value", Integer.valueOf(data.getValue()));
            } else {
                innerObject.addProperty("value", data.getValue());
            }
        }

        if (data.getUnit() != null) {
            innerObject.addProperty("unit", data.getUnit());
        }

        JsonObject dataObject = new JsonObject();
        dataObject.add("data", innerObject);
        String requestBodyPayload = dataObject.toString();

        if (logger.isDebugEnabled()) {
            logger.debug("Send data(PUT {}) \n{}", path, requestBodyPayload);
        }

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, requestBodyPayload);

        checkOrRefreshAccessToken();
        Request request = new Request.Builder().url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).addHeader("Authorization", "Bearer " + getAccessToken())
                .build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[putData({}, {}, {})] Response code: {} \n{}", haId, path, data, response.code(),
                        toPrettyFormat(body));
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[putData({}, {}, {})] Retrying method.", haId, path, data);
            putData(haId, path, data, asInt);
        }
    }

    private synchronized void putOption(String haId, String path, Option option, boolean asInt)
            throws CommunicationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", option.getKey());

        if (option.getValue() != null) {
            if (asInt) {
                innerObject.addProperty("value", Integer.valueOf(option.getValue()));
            } else {
                innerObject.addProperty("value", option.getValue());
            }
        }

        if (option.getUnit() != null) {
            innerObject.addProperty("unit", option.getUnit());
        }

        JsonArray optionsArray = new JsonArray();
        optionsArray.add(innerObject);

        JsonObject optionsObject = new JsonObject();
        optionsObject.add("options", optionsArray);

        JsonObject dataObject = new JsonObject();
        dataObject.add("data", optionsObject);

        String requestBodyPayload = dataObject.toString();

        if (logger.isDebugEnabled()) {
            logger.debug("Send data(PUT {}) \n{}", path, requestBodyPayload);
        }

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, requestBodyPayload);

        checkOrRefreshAccessToken();
        Request request = new Request.Builder().url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).addHeader("Authorization", "Bearer " + getAccessToken())
                .build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[putOption({}, {}, {})] Response code: {} \n{}", haId, path, option, response.code(),
                        toPrettyFormat(body));
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        } catch (InvalidTokenException e) {
            setAccessToken(null);
            logger.debug("[putOption({}, {}, {})] Retrying method.", haId, path, option);
            putOption(haId, path, option, asInt);
        }
    }

    private String getAccessToken() {
        return accessToken;
    }

    private String setAccessToken(String token) {
        return this.accessToken = token;
    }

    private void checkOrRefreshAccessToken() throws CommunicationException {
        if (isEmpty(getAccessToken())) {
            String newAccessToken = newAccessTokenFunction.get();
            if (isEmpty(newAccessToken)) {
                throw new CommunicationException("Could not get access token!");
            }
            setAccessToken(newAccessToken);
        }
    }

    private void checkResponseCode(int desiredCode, Response response)
            throws CommunicationException, InvalidTokenException {
        checkResponseCode(Arrays.asList(desiredCode), response);
    }

    private void checkResponseCode(List<Integer> desiredCodes, Response response)
            throws CommunicationException, InvalidTokenException {

        if (!desiredCodes.contains(HTTP_UNAUTHORIZED) && response.code() == HTTP_UNAUTHORIZED) {
            logger.debug("[oAuth] Current access token is invalid --> need to refresh!");
            throw new InvalidTokenException("Token invalid!");
        }

        if (!desiredCodes.contains(response.code())) {
            int code = response.code();
            String message = response.message();
            String body = "";
            try {
                body = response.body().string();
            } catch (IOException e) {
                logger.error("Could not get HTTP response body as string", e);
            }

            throw new CommunicationException(code, message, body);
        }
    }

    private Program mapToProgram(String json) {
        final ArrayList<Option> optionList = new ArrayList<>();
        Program result = null;

        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        result = new Program(data.get("key").getAsString(), optionList);
        JsonArray options = data.getAsJsonArray("options");

        options.forEach(option -> {
            JsonObject obj = (JsonObject) option;

            String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
            String value = obj.get("value") != null && !obj.get("value").isJsonNull() ? obj.get("value").getAsString()
                    : null;
            String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

            optionList.add(new Option(key, value, unit));
        });

        return result;
    }

    private List<AvailableProgram> mapToAvailablePrograms(String json) {
        ArrayList<AvailableProgram> result = new ArrayList<>();

        try {
            JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

            JsonArray programs = responseObject.getAsJsonObject("data").getAsJsonArray("programs");
            programs.forEach(program -> {
                JsonObject obj = (JsonObject) program;
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                JsonObject constraints = obj.getAsJsonObject("constraints");
                boolean available = constraints.get("available") != null ? constraints.get("available").getAsBoolean()
                        : false;
                String execution = constraints.get("execution") != null ? constraints.get("execution").getAsString()
                        : null;

                result.add(new AvailableProgram(key, available, execution));
            });
        } catch (Exception e) {
            logger.error("Could not parse available programs response!", e.getMessage());
        }

        return result;
    }

    private List<AvailableProgramOption> mapToAvailableProgramOption(String json) {
        ArrayList<AvailableProgramOption> result = new ArrayList<>();

        try {
            JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

            JsonArray options = responseObject.getAsJsonObject("data").getAsJsonArray("options");
            options.forEach(option -> {
                JsonObject obj = (JsonObject) option;
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                ArrayList<String> allowedValues = new ArrayList<>();
                obj.getAsJsonObject("constraints").getAsJsonArray("allowedvalues")
                        .forEach(value -> allowedValues.add(value.getAsString()));

                result.add(new AvailableProgramOption(key, allowedValues));
            });
        } catch (Exception e) {
            logger.error("Could not parse available program options response!", e.getMessage());
        }

        return result;
    }

    private HomeAppliance mapToHomeAppliance(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        return new HomeAppliance(data.get("haId").getAsString(), data.get("name").getAsString(),
                data.get("brand").getAsString(), data.get("vib").getAsString(), data.get("connected").getAsBoolean(),
                data.get("type").getAsString(), data.get("enumber").getAsString());
    }

    private ArrayList<HomeAppliance> mapToHomeAppliances(String json) {
        final ArrayList<HomeAppliance> result = new ArrayList<>();
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        JsonArray homeappliances = data.getAsJsonArray("homeappliances");

        homeappliances.forEach(appliance -> {
            JsonObject obj = (JsonObject) appliance;

            result.add(new HomeAppliance(obj.get("haId").getAsString(), obj.get("name").getAsString(),
                    obj.get("brand").getAsString(), obj.get("vib").getAsString(), obj.get("connected").getAsBoolean(),
                    obj.get("type").getAsString(), obj.get("enumber").getAsString()));
        });

        return result;
    }

    private Data mapToState(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        String unit = data.get("unit") != null ? data.get("unit").getAsString() : null;

        return new Data(data.get("key").getAsString(), data.get("value").getAsString(), unit);
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

    private Request createGetRequest(String path) {
        return new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + getAccessToken()).build();
    }

    private String toPrettyFormat(String jsonString) {
        try {
            JsonParser parser = new JsonParser();
            JsonObject json = parser.parse(jsonString).getAsJsonObject();

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String prettyJson = gson.toJson(json);
            return prettyJson;
        } catch (Exception e) {
            return jsonString;
        }
    }

}