/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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

import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.time.LocalDateTime.now;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang.StringUtils.containsIgnoreCase;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.API_BASE_URL;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.API_SIMULATOR_BASE_URL;
import static org.openhab.binding.homeconnect.internal.client.OkHttpHelper.formatJsonBody;
import static org.openhab.binding.homeconnect.internal.client.OkHttpHelper.requestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.QueueUtils;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.OfflineException;
import org.openhab.binding.homeconnect.internal.client.model.ApiRequest;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgram;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgramOption;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.HomeConnectRequest;
import org.openhab.binding.homeconnect.internal.client.model.HomeConnectResponse;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Client for Home Connect API.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
@NonNullByDefault
public class HomeConnectApiClient {
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String BSH_JSON_V1 = "application/vnd.bsh.sdk.v1+json";
    private static final MediaType BSH_JSON_V1_MEDIA_TYPE = requireNonNull(MediaType.parse(BSH_JSON_V1));
    private static final int REQUEST_READ_TIMEOUT = 30;
    private static final int VALUE_TYPE_STRING = 0;
    private static final int VALUE_TYPE_INT = 1;
    private static final int VALUE_TYPE_BOOLEAN = 2;
    private static final int COMMUNICATION_QUEUE_SIZE = 50;

    private final Logger logger;
    private final OkHttpClient client;
    private final String apiUrl;
    private final Map<String, List<AvailableProgramOption>> availableProgramOptionsCache;
    private final OAuthClientService oAuthClientService;
    private final Queue<ApiRequest> communicationQueue;
    private final JsonParser jsonParser;

    public HomeConnectApiClient(OAuthClientService oAuthClientService, boolean simulated,
            @Nullable List<ApiRequest> apiRequestHistory) {
        this.oAuthClientService = oAuthClientService;

        availableProgramOptionsCache = new ConcurrentHashMap<>();
        apiUrl = simulated ? API_SIMULATOR_BASE_URL : API_BASE_URL;
        client = OkHttpHelper.builder(true).readTimeout(REQUEST_READ_TIMEOUT, TimeUnit.SECONDS).build();
        logger = LoggerFactory.getLogger(HomeConnectApiClient.class);
        jsonParser = new JsonParser();
        communicationQueue = QueueUtils.synchronizedQueue(new CircularFifoQueue<>(COMMUNICATION_QUEUE_SIZE));
        if (apiRequestHistory != null) {
            communicationQueue.addAll(apiRequestHistory);
        }
    }

    /**
     * Get all home appliances
     *
     * @return list of {@link HomeAppliance}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public List<HomeAppliance> getHomeAppliances() throws CommunicationException, AuthorizationException {
        Request request = createGetRequest("/api/homeappliances");
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, null, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(null, request, null, response, responseBody);

            return mapToHomeAppliances(responseBody);
        } catch (IOException e) {
            logger.warn("Failed to fetch home appliances! error={}", e.getMessage());
            trackAndLogApiRequest(null, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    /**
     * Get home appliance by id
     *
     * @param haId home appliance id
     * @return {@link HomeAppliance}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public HomeAppliance getHomeAppliance(String haId) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest("/api/homeappliances/" + haId);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            return mapToHomeAppliance(responseBody);
        } catch (IOException e) {
            logger.warn("Failed to get home appliance! haId={}, error={}", haId, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    /**
     * Get power state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getPowerState(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "BSH.Common.Setting.PowerState");
    }

    /**
     * Set power state of device.
     *
     * @param haId home appliance id
     * @param state target state
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public void setPowerState(String haId, String state) throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("BSH.Common.Setting.PowerState", state, null));
    }

    /**
     * Get setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getFreezerSetpointTemperature(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer");
    }

    /**
     * Set setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public void setFreezerSetpointTemperature(String haId, String state, String unit)
            throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer", state, unit),
                VALUE_TYPE_INT);
    }

    /**
     * Get setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getFridgeSetpointTemperature(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator");
    }

    /**
     * Set setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public void setFridgeSetpointTemperature(String haId, String state, String unit)
            throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator", state, unit),
                VALUE_TYPE_INT);
    }

    /**
     * Get fridge super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getFridgeSuperMode(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator");
    }

    /**
     * Set fridge super mode
     *
     * @param haId home appliance id
     * @param enable enable or disable fridge super mode
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public void setFridgeSuperMode(String haId, boolean enable) throws CommunicationException, AuthorizationException {
        putSettings(haId,
                new Data("Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator", String.valueOf(enable), null),
                VALUE_TYPE_BOOLEAN);
    }

    /**
     * Get freezer super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getFreezerSuperMode(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer");
    }

    /**
     * Set freezer super mode
     *
     * @param haId home appliance id
     * @param enable enable or disable freezer super mode
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public void setFreezerSuperMode(String haId, boolean enable) throws CommunicationException, AuthorizationException {
        putSettings(haId,
                new Data("Refrigeration.FridgeFreezer.Setting.SuperModeFreezer", String.valueOf(enable), null),
                VALUE_TYPE_BOOLEAN);
    }

    /**
     * Get door state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getDoorState(String haId) throws CommunicationException, AuthorizationException {
        return getStatus(haId, "BSH.Common.Status.DoorState");
    }

    /**
     * Get operation state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getOperationState(String haId) throws CommunicationException, AuthorizationException {
        return getStatus(haId, "BSH.Common.Status.OperationState");
    }

    /**
     * Get current cavity temperature of oven.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public Data getCurrentCavityTemperature(String haId) throws CommunicationException, AuthorizationException {
        return getStatus(haId, "Cooking.Oven.Status.CurrentCavityTemperature");
    }

    /**
     * Is remote start allowed?
     *
     * @param haId haId home appliance id
     * @return true or false
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public boolean isRemoteControlStartAllowed(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlStartAllowed");
        return "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is remote control allowed?
     *
     * @param haId haId home appliance id
     * @return true or false
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public boolean isRemoteControlActive(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlActive");
        return "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is local control allowed?
     *
     * @param haId haId home appliance id
     * @return true or false
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public boolean isLocalControlActive(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.LocalControlActive");
        return "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Get active program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no active program
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public @Nullable Program getActiveProgram(String haId) throws CommunicationException, AuthorizationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    /**
     * Get selected program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no selected program
     * @throws CommunicationException API communication exception
     * @throws AuthorizationException oAuth authorization exception
     */
    public @Nullable Program getSelectedProgram(String haId) throws CommunicationException, AuthorizationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/selected");
    }

    public void setSelectedProgram(String haId, String program) throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/selected", new Data(program, null, null),
                VALUE_TYPE_STRING);
    }

    public void startProgram(String haId, String program) throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/active", new Data(program, null, null),
                VALUE_TYPE_STRING);
    }

    public void startSelectedProgram(String haId) throws CommunicationException, AuthorizationException {
        @Nullable
        String selectedProgram = getRaw(haId, "/api/homeappliances/" + haId + "/programs/selected");
        if (selectedProgram != null) {
            putRaw(haId, "/api/homeappliances/" + haId + "/programs/active", selectedProgram);
        }
    }

    public void startCustomProgram(String haId, String json) throws CommunicationException, AuthorizationException {
        putRaw(haId, "/api/homeappliances/" + haId + "/programs/active", json);
    }

    public void setProgramOptions(String haId, String key, String value, @Nullable String unit, boolean valueAsInt,
            boolean isProgramActive) throws CommunicationException, AuthorizationException {
        String programState = isProgramActive ? "active" : "selected";

        putOption(haId, "/api/homeappliances/" + haId + "/programs/" + programState + "/options",
                new Option(key, value, unit), valueAsInt);
    }

    public void stopProgram(String haId) throws CommunicationException, AuthorizationException {
        sendDelete(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    public List<AvailableProgram> getPrograms(String haId) throws CommunicationException, AuthorizationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs");
    }

    public List<AvailableProgram> getAvailablePrograms(String haId)
            throws CommunicationException, AuthorizationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs/available");
    }

    public List<AvailableProgramOption> getProgramOptions(String haId, String programKey)
            throws CommunicationException, AuthorizationException {
        if (availableProgramOptionsCache.containsKey(programKey)) {
            logger.debug("Returning cached options for '{}'.", programKey);
            return availableProgramOptionsCache.get(programKey);
        }

        String path = "/api/homeappliances/" + haId + "/programs/available/" + programKey;
        Request request = createGetRequest(path);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            List<AvailableProgramOption> availableProgramOptions = mapToAvailableProgramOption(responseBody, haId);
            availableProgramOptionsCache.put(programKey, availableProgramOptions);
            return availableProgramOptions;
        } catch (IOException e) {
            logger.warn("Failed to get program options! haId={}, programKey={}, error={}", haId, programKey,
                    e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    /**
     * Get latest API requests.
     *
     * @return thread safe queue
     */
    public Queue<ApiRequest> getLatestApiRequests() {
        return communicationQueue;
    }

    private Data getSetting(String haId, String setting) throws CommunicationException, AuthorizationException {
        return getData(haId, "/api/homeappliances/" + haId + "/settings/" + setting);
    }

    private void putSettings(String haId, Data data) throws CommunicationException, AuthorizationException {
        putSettings(haId, data, VALUE_TYPE_STRING);
    }

    private void putSettings(String haId, Data data, int valueType)
            throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/settings/" + data.getName(), data, valueType);
    }

    private Data getStatus(String haId, String status) throws CommunicationException, AuthorizationException {
        return getData(haId, "/api/homeappliances/" + haId + "/status/" + status);
    }

    public @Nullable String getRaw(String haId, String path) throws CommunicationException, AuthorizationException {
        return getRaw(haId, path, false);
    }

    public @Nullable String getRaw(String haId, String path, boolean ignoreResponseCode)
            throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            if (ignoreResponseCode || response.code() == HTTP_OK) {
                return responseBody;
            }
        } catch (IOException e) {
            logger.warn("Failed to get raw! haId={}, path={}, error={}", haId, path, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
        return null;
    }

    public String putRaw(String haId, String path, String requestBodyPayload)
            throws CommunicationException, AuthorizationException {
        RequestBody requestBody = RequestBody.create(BSH_JSON_V1_MEDIA_TYPE,
                requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, request, response, haId, requestBodyPayload);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, requestBodyPayload, response, responseBody);
            return responseBody;
        } catch (IOException e) {
            logger.warn("Failed to put raw! haId={}, path={}, payload={}, error={}", haId, path, requestBodyPayload,
                    e.getMessage());
            trackAndLogApiRequest(haId, request, requestBodyPayload, null, null);
            throw new CommunicationException(e);
        }
    }

    private @Nullable Program getProgram(String haId, String path)
            throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(asList(HTTP_OK, HTTP_NOT_FOUND), request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            if (response.code() == HTTP_OK) {
                return mapToProgram(responseBody);
            }
        } catch (IOException e) {
            logger.warn("Failed to get program! haId={}, path={}, error={}", haId, path, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
        return null;
    }

    private List<AvailableProgram> getAvailablePrograms(String haId, String path)
            throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            return mapToAvailablePrograms(responseBody, haId);
        } catch (IOException e) {
            logger.warn("Failed to get available programs! haId={}, path={}, error={}", haId, path, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    private void sendDelete(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).delete()
                .build();
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, request, response, haId, null);

            trackAndLogApiRequest(haId, request, null, response, mapToString(response.body()));
        } catch (IOException e) {
            logger.warn("Failed to send delete! haId={}, path={}, error={}", haId, path, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    private Data getData(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, request, response, haId, null);

            String responseBody = mapToString(response.body());
            trackAndLogApiRequest(haId, request, null, response, responseBody);

            return mapToState(responseBody);
        } catch (IOException e) {
            logger.warn("Failed to get data! haId={}, path={}, error={}", haId, path, e.getMessage());
            trackAndLogApiRequest(haId, request, null, null, null);
            throw new CommunicationException(e);
        }
    }

    private void putData(String haId, String path, Data data, int valueType)
            throws CommunicationException, AuthorizationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", data.getName());

        if (data.getValue() != null) {
            if (valueType == VALUE_TYPE_INT) {
                innerObject.addProperty("value", Integer.valueOf(data.getValue()));
            } else if (valueType == VALUE_TYPE_BOOLEAN) {
                innerObject.addProperty("value", Boolean.valueOf(data.getValue()));
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

        RequestBody requestBody = RequestBody.create(BSH_JSON_V1_MEDIA_TYPE,
                requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, request, response, haId, requestBodyPayload);

            trackAndLogApiRequest(haId, request, requestBodyPayload, response, mapToString(response.body()));
        } catch (IOException e) {
            logger.warn("Failed to put data! haId={}, path={}, data={}, valueType={}, error={}", haId, path, data,
                    valueType, e.getMessage());
            trackAndLogApiRequest(haId, request, requestBodyPayload, null, null);
            throw new CommunicationException(e);
        }
    }

    private void putOption(String haId, String path, Option option, boolean asInt)
            throws CommunicationException, AuthorizationException {
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

        RequestBody requestBody = RequestBody.create(BSH_JSON_V1_MEDIA_TYPE,
                requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();
        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, request, response, haId, requestBodyPayload);

            trackAndLogApiRequest(haId, request, requestBodyPayload, response, mapToString(response.body()));
        } catch (IOException e) {
            logger.warn("Failed to put option! haId={}, path={}, option={}, asInt={}, error={}", haId, path, option,
                    asInt, e.getMessage());
            trackAndLogApiRequest(haId, request, requestBodyPayload, null, null);
            throw new CommunicationException(e);
        }
    }

    private void checkResponseCode(int desiredCode, Request request, Response response, @Nullable String haId,
            @Nullable String requestPayload) throws CommunicationException, AuthorizationException {
        checkResponseCode(singletonList(desiredCode), request, response, haId, requestPayload);
    }

    private void checkResponseCode(List<Integer> desiredCodes, Request request, Response response,
            @Nullable String haId, @Nullable String requestPayload)
            throws CommunicationException, AuthorizationException {

        if (!desiredCodes.contains(HTTP_UNAUTHORIZED) && response.code() == HTTP_UNAUTHORIZED) {
            logger.debug("Current access token is invalid.");
            String responseBody = "";
            try {
                responseBody = mapToString(response.body());
            } catch (IOException e) {
                logger.error("Could not get HTTP response body as string.", e);
            }
            trackAndLogApiRequest(haId, request, requestPayload, response, responseBody);
            throw new AuthorizationException("Token invalid!");
        }

        if (!desiredCodes.contains(response.code())) {
            int code = response.code();
            String message = response.message();

            logger.debug("Invalid HTTP response code {} (allowed: {})", code, desiredCodes);
            String responseBody = "";
            try {
                responseBody = mapToString(response.body());
            } catch (IOException e) {
                logger.error("Could not get HTTP response body as string.", e);
            }
            trackAndLogApiRequest(haId, request, requestPayload, response, responseBody);

            if (code == HTTP_CONFLICT && containsIgnoreCase(responseBody, "error")
                    && containsIgnoreCase(responseBody, "offline")) {
                throw new OfflineException(code, message, responseBody);
            } else {
                throw new CommunicationException(code, message, responseBody);
            }
        }
    }

    private String mapToString(@Nullable ResponseBody responseBody) throws IOException {
        if (responseBody != null) {
            return responseBody.string();
        }
        return "";
    }

    private Program mapToProgram(String json) {
        ArrayList<Option> optionList = new ArrayList<>();
        JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();
        JsonObject data = responseObject.getAsJsonObject("data");
        Program result = new Program(data.get("key").getAsString(), optionList);
        JsonArray options = data.getAsJsonArray("options");

        options.forEach(option -> {
            JsonObject obj = (JsonObject) option;

            @Nullable
            String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
            @Nullable
            String value = obj.get("value") != null && !obj.get("value").isJsonNull() ? obj.get("value").getAsString()
                    : null;
            @Nullable
            String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

            optionList.add(new Option(key, value, unit));
        });

        return result;
    }

    private List<AvailableProgram> mapToAvailablePrograms(String json, String haId) {
        ArrayList<AvailableProgram> result = new ArrayList<>();

        try {
            JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();

            JsonArray programs = responseObject.getAsJsonObject("data").getAsJsonArray("programs");
            programs.forEach(program -> {
                JsonObject obj = (JsonObject) program;
                @Nullable
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                JsonObject constraints = obj.getAsJsonObject("constraints");
                boolean available = constraints.get("available") != null && constraints.get("available").getAsBoolean();
                @Nullable
                String execution = constraints.get("execution") != null ? constraints.get("execution").getAsString()
                        : null;

                if (key != null && execution != null) {
                    result.add(new AvailableProgram(key, available, execution));
                }
            });
        } catch (Exception e) {
            logger.error("Could not parse available programs response! haId={}, error={}", haId, e.getMessage());
        }

        return result;
    }

    private List<AvailableProgramOption> mapToAvailableProgramOption(String json, String haId) {
        ArrayList<AvailableProgramOption> result = new ArrayList<>();

        try {
            JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();

            JsonArray options = responseObject.getAsJsonObject("data").getAsJsonArray("options");
            options.forEach(option -> {
                JsonObject obj = (JsonObject) option;
                @Nullable
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                ArrayList<String> allowedValues = new ArrayList<>();
                obj.getAsJsonObject("constraints").getAsJsonArray("allowedvalues")
                        .forEach(value -> allowedValues.add(value.getAsString()));

                if (key != null) {
                    result.add(new AvailableProgramOption(key, allowedValues));
                }
            });
        } catch (Exception e) {
            logger.warn("Could not parse available program options response! haId={}, error={}", haId, e.getMessage());
        }

        return result;
    }

    private HomeAppliance mapToHomeAppliance(String json) {
        JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        return new HomeAppliance(data.get("haId").getAsString(), data.get("name").getAsString(),
                data.get("brand").getAsString(), data.get("vib").getAsString(), data.get("connected").getAsBoolean(),
                data.get("type").getAsString(), data.get("enumber").getAsString());
    }

    private ArrayList<HomeAppliance> mapToHomeAppliances(String json) {
        final ArrayList<HomeAppliance> result = new ArrayList<>();
        JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();

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
        JsonObject responseObject = jsonParser.parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        @Nullable
        String unit = data.get("unit") != null ? data.get("unit").getAsString() : null;

        return new Data(data.get("key").getAsString(), data.get("value").getAsString(), unit);
    }

    private Request createGetRequest(String path) throws AuthorizationException, CommunicationException {
        return requestBuilder(oAuthClientService).url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get().build();
    }

    private void trackAndLogApiRequest(@Nullable String haId, Request request, @Nullable String requestBody,
            @Nullable Response response, @Nullable String responseBody) {
        HomeConnectRequest homeConnectRequest = map(request, requestBody);
        @Nullable
        HomeConnectResponse homeConnectResponse = response != null ? map(response, responseBody) : null;

        logApiRequest(haId, homeConnectRequest, homeConnectResponse);
        trackApiRequest(homeConnectRequest, homeConnectResponse);
    }

    private void logApiRequest(@Nullable String haId, HomeConnectRequest homeConnectRequest,
            @Nullable HomeConnectResponse homeConnectResponse) {
        if (logger.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();

            if (haId != null) {
                sb.append("[").append(haId).append("] ");
            }

            sb.append(homeConnectRequest.getMethod()).append(" ");
            if (homeConnectResponse != null) {
                sb.append(homeConnectResponse.getCode()).append(" ");
            }
            sb.append(homeConnectRequest.getUrl()).append("\n");
            homeConnectRequest.getHeader()
                    .forEach((key, value) -> sb.append("> ").append(key).append(": ").append(value).append("\n"));

            if (homeConnectRequest.getBody() != null) {
                sb.append(homeConnectRequest.getBody()).append("\n");
            }

            if (homeConnectResponse != null) {
                sb.append("\n");
                homeConnectResponse.getHeader()
                        .forEach((key, value) -> sb.append("< ").append(key).append(": ").append(value).append("\n"));
            }
            if (homeConnectResponse != null && homeConnectResponse.getBody() != null) {
                sb.append(homeConnectResponse.getBody()).append("\n");
            }

            logger.debug(sb.toString());
        }
    }

    private void trackApiRequest(HomeConnectRequest homeConnectRequest,
            @Nullable HomeConnectResponse homeConnectResponse) {
        communicationQueue.add(new ApiRequest(now(), homeConnectRequest, homeConnectResponse));
    }

    private HomeConnectRequest map(Request request, @Nullable String requestBody) {
        HashMap<String, String> headers = new HashMap<>();
        request.headers().toMultimap().forEach((key, values) -> headers.put(key, values.toString()));

        return new HomeConnectRequest(request.url().toString(), request.method(), headers,
                requestBody != null ? formatJsonBody(requestBody) : null);
    }

    private HomeConnectResponse map(Response response, @Nullable String responseBody) {
        HashMap<String, String> headers = new HashMap<>();
        response.headers().toMultimap().forEach((key, values) -> headers.put(key, values.toString()));

        return new HomeConnectResponse(response.code(), headers,
                responseBody != null ? formatJsonBody(responseBody) : null);
    }
}
