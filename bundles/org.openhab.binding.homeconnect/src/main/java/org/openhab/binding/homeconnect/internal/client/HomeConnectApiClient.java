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
import org.openhab.binding.homeconnect.internal.client.exception.ConfigurationException;
import org.openhab.binding.homeconnect.internal.client.exception.InvalidTokenException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final static String BSH_JSON_V1 = "application/vnd.bsh.sdk.v1+json";
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

    private OkSse oksse;

    public HomeConnectApiClient(String accessToken, boolean simulated, Supplier<String> newAccessTokenFunction) {
        this.accessToken = accessToken;
        this.newAccessTokenFunction = newAccessTokenFunction;

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
     * @return list of {@link HomeAppliance} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public synchronized List<HomeAppliance> getHomeAppliances() throws ConfigurationException, CommunicationException {
        checkAccessToken();

        Request request = new Request.Builder().url(apiUrl + "/api/homeappliances").header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + getToken()).build();

        try (Response response = client.newCall(request).execute()) {

            checkResponseCode(HTTP_OK, response);

            String body = response.body().string();
            logger.debug("[getHomeAppliances()] Response code: {}, body: {}", response.code(), body);

            return mapToHomeAppliances(body);

        } catch (IOException e) {
            logger.error("Token does not work! {}", e.getMessage());
        } catch (InvalidTokenException e) {
            logger.debug("[getHomeAppliances()] Retrying method.");
            return getHomeAppliances();
        }

        return null;
    }

    /**
     * Get home appliance by id
     *
     * @param haId home appliance id
     * @return {@link HomeAppliance} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public synchronized HomeAppliance getHomeAppliance(String haId)
            throws ConfigurationException, CommunicationException {
        checkAccessToken();

        Request request = new Request.Builder().url(apiUrl + "/api/homeappliances/" + haId).header(ACCEPT, BSH_JSON_V1)
                .get().addHeader("Authorization", "Bearer " + getToken()).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            logger.debug("[getHomeAppliance({})] Response code: {}, body: {}", haId, response.code(), body);

            return mapToHomeAppliance(body);
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        } catch (InvalidTokenException e) {
            logger.debug("[getHomeAppliance({})] Retrying method.", haId);
            return getHomeAppliance(haId);
        }

        return null;
    }

    /**
     * Get power state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getPowerState(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "BSH.Common.Setting.PowerState");
    }

    /**
     * Set power state of device.
     *
     * @param haId  home appliance id
     * @param state target state
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public void setPowerState(String haId, String state) throws ConfigurationException, CommunicationException {
        putSettings(haId, new Data("BSH.Common.Setting.PowerState", state, null));
    }

    /**
     * Get setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public Data getFreezerSetpointTemperature(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer");
    }

    /**
     * Set setpoint temperature of freezer
     *
     * @param haId  home appliance id
     * @param state new temperature
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public void setFreezerSetpointTemperature(String haId, String state, String unit)
            throws ConfigurationException, CommunicationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer", state, unit),
                true);
    }

    /**
     * Get setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public Data getFridgeSetpointTemperature(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator");
    }

    /**
     * Set setpoint temperature of fridge
     *
     * @param haId  home appliance id
     * @param state new temperature
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public void setFridgeSetpointTemperature(String haId, String state, String unit)
            throws ConfigurationException, CommunicationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator", state, unit),
                true);
    }

    /**
     * Get fridge super mode
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public Data getFridgeSuperMode(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator");
    }

    /**
     * Get freezer super mode
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public Data getFreezerSuperMode(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer");
    }

    /**
     * Get door state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getDoorState(String haId) throws ConfigurationException, CommunicationException {
        return getStatus(haId, "BSH.Common.Status.DoorState");
    }

    /**
     * Get operation state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getOperationState(String haId) throws ConfigurationException, CommunicationException {
        return getStatus(haId, "BSH.Common.Status.OperationState");
    }

    /**
     * Is remote start allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public boolean isRemoteControlStartAllowed(String haId) throws ConfigurationException, CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlStartAllowed");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is remote control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public boolean isRemoteControlActive(String haId) throws ConfigurationException, CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Get active program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error or if there is no active program
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Program getActiveProgram(String haId) throws ConfigurationException, CommunicationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    /**
     * Get selected program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error or if there is no selected program
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Program getSelectedProgram(String haId) throws ConfigurationException, CommunicationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/selected");
    }

    /**
     * Register {@link ServerSentEventListener} to receive SSE events by Home Conncet API. This helps to reduce the
     * amount of request you would usually need to update all channels.
     *
     * Checkout rate limits of the API at. https://developer.home-connect.com/docs/general/ratelimiting
     *
     * @param eventListener
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public synchronized void registerEventListener(ServerSentEventListener eventListener)
            throws ConfigurationException, CommunicationException {
        String haId = eventListener.haId();

        logger.debug("Register event listener: {}", eventListener);
        eventListeners.add(eventListener);

        if (!serverSentEvent.containsKey(haId)) {
            checkAccessToken();
            Request request = new Request.Builder().url(apiUrl + "/api/homeappliances/" + haId + "/events")
                    .addHeader("Authorization", "Bearer " + getToken()).build();

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
                            logger.debug("[{}] SSE received id: {} event: {} message:{}", haId, id, event, message);
                        }
                    }

                    if (!isEmpty(message) && !EMPTY_EVENT.equals(message)) {
                        logger.debug("{}", message.getBytes());
                        ArrayList<Event> events = mapToEvents(message);
                        events.forEach(e -> eventListeners.forEach(listener -> {
                            if (listener.haId().equals(haId)) {
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
                    boolean ret = true;
                    if (logger.isDebugEnabled() && throwable != null) {
                        logger.debug("[{}] SSE error.", haId, throwable);
                    }

                    if (response != null && response.code() == HTTP_FORBIDDEN) {
                        logger.warn(
                                "[{}] Stopping SSE listener! Got FORBIDDEN response from server. Please check if you allowed to access this device.",
                                haId);
                        ret = false;
                    }

                    if (response != null && response.code() == HTTP_UNAUTHORIZED) {
                        logger.error("SSE token became invalid --> close SSE");

                        // invalidate old token
                        synchronized (HomeConnectApiClient.this) {
                            setAccessToken(null);
                            try {
                                checkAccessToken();
                                serverSentEvent.remove(haId);
                                eventListeners.remove(eventListener);
                                registerEventListener(eventListener);
                                sse.close();

                            } catch (ConfigurationException | CommunicationException e) {
                                logger.error("Could not refresh token!", e);
                            }
                        }

                        ret = false;
                    }

                    if (response != null) {
                        response.close();
                    }
                    return ret; // True to retry, false otherwise
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

    private Data getSetting(String haId, String setting) throws ConfigurationException, CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/settings/" + setting);
    }

    private void putSettings(String haId, Data data) throws ConfigurationException, CommunicationException {
        putSettings(haId, data, false);
    }

    private void putSettings(String haId, Data data, boolean asInt)
            throws ConfigurationException, CommunicationException {
        putData(haId, "/api/homeappliances/" + haId + "/settings/" + data.getName(), data, asInt);
    }

    private Data getStatus(String haId, String status) throws ConfigurationException, CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/status/" + status);
    }

    private synchronized Program getProgram(String haId, String path)
            throws ConfigurationException, CommunicationException {
        checkAccessToken();

        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + getToken()).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK, HTTP_NOT_FOUND), response);
            String body = response.body().string();
            logger.debug("[getProgram({}, {})] Response code: {}, body: {}", haId, path, response.code(), body);

            if (response.code() == HTTP_OK) {
                return mapToProgram(body);
            }
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        } catch (InvalidTokenException e) {
            logger.debug("[getProgram({}, {})] Retrying method.", haId, path);
            return getProgram(haId, path);
        }

        return null;
    }

    private synchronized Data getData(String haId, String path) throws ConfigurationException, CommunicationException {
        checkAccessToken();

        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + getToken()).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            logger.debug("[getData({}, {})] Response code: {}, body: {}", haId, path, response.code(), body);

            return mapToState(body);
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        } catch (InvalidTokenException e) {
            logger.debug("[getData({}, {})] Retrying method.", haId, path);
            return getData(haId, path);
        }

        return null;
    }

    private synchronized void putData(String haId, String path, Data data, boolean asInt)
            throws ConfigurationException, CommunicationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", data.getName());
        if (asInt) {
            innerObject.addProperty("value", Integer.valueOf(data.getValue()));
        } else {
            innerObject.addProperty("value", data.getValue());
        }

        if (data.getUnit() != null) {
            innerObject.addProperty("unit", data.getUnit());
        }

        JsonObject dataObject = new JsonObject();
        dataObject.add("data", innerObject);

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, dataObject.toString());

        checkAccessToken();
        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).put(requestBody)
                .addHeader("Authorization", "Bearer " + getToken()).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            logger.debug("[putData({}, {}, {})] Response code: {} body: {}", haId, path, data, response.code(), body);

        } catch (IOException e) {
            logger.error("Token does not work!", e);
        } catch (InvalidTokenException e) {
            logger.debug("[putData({}, {}, {})] Retrying method.", haId, path, data);
            putData(haId, path, data, asInt);
        }
    }

    private String getToken() {
        return accessToken;
    }

    private String setAccessToken(String token) {
        return this.accessToken = token;
    }

    private void checkAccessToken() throws CommunicationException {
        if (isEmpty(accessToken)) {
            String newAccessToken = newAccessTokenFunction.get();
            if (isEmpty(newAccessToken)) {
                accessToken = null;
                throw new CommunicationException("Could not get access token!");
            }
        }
    }

    private void checkResponseCode(int desiredCode, Response response)
            throws CommunicationException, IOException, InvalidTokenException, ConfigurationException {
        checkResponseCode(Arrays.asList(desiredCode), response);
    }

    private void checkResponseCode(List<Integer> desiredCodes, Response response)
            throws CommunicationException, IOException, InvalidTokenException, ConfigurationException {

        if (!desiredCodes.contains(HTTP_UNAUTHORIZED) && response.code() == HTTP_UNAUTHORIZED) {
            logger.debug("[oAuth] Current token is invalid --> need to refresh!");
            setAccessToken(null);

            throw new InvalidTokenException("Token invalid!");
        }

        if (!desiredCodes.contains(response.code())) {
            int code = response.code();
            String message = response.message();

            throw new CommunicationException(code, message, response.body().string());
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

}