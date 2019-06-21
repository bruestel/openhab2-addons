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
package org.openhab.binding.homeconnect.internal.handler;

import static org.eclipse.smarthome.core.library.unit.ImperialUnits.FAHRENHEIT;
import static org.eclipse.smarthome.core.library.unit.SIUnits.CELSIUS;
import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.ConfigurationException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AbstractHomeConnectThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jonas Brüstel - Initial contribution
 */
public abstract class AbstractHomeConnectThingHandler extends BaseThingHandler implements HomeConnectApiClientListener {

    private final Logger logger = LoggerFactory.getLogger(AbstractHomeConnectThingHandler.class);

    @Nullable
    private ServerSentEventListener serverSentEventListener;

    @Nullable
    private HomeConnectApiClient client;

    @NonNull
    private ConcurrentHashMap<String, EventHandler> eventHandlers;

    @NonNull
    private ConcurrentHashMap<String, ChannelUpdateHandler> channelUpdateHandlers;

    public AbstractHomeConnectThingHandler(Thing thing) {
        super(thing);
        eventHandlers = new ConcurrentHashMap<>();
        channelUpdateHandlers = new ConcurrentHashMap<>();
    }

    @Override
    public void initialize() {
        // wait for bridge to be setup first
        updateStatus(ThingStatus.OFFLINE);

        // if handler configuration is updated, re-register Server Sent Event Listener
        HomeConnectApiClient hcac = client;
        if (hcac != null) {
            refreshConnectionStatus();

            if (serverSentEventListener != null) {
                logger.debug("Thing configuration might have changed --> re-register Server Sent Events listener.");
                hcac.unregisterEventListener(serverSentEventListener);
                try {
                    hcac.registerEventListener(serverSentEventListener);
                } catch (ConfigurationException | CommunicationException e) {
                    logger.error("API communication problem!", e);
                }
            }

            updateChannels();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        Bridge bridge = getBridge();
        HomeConnectApiClient apiClient = client;
        if (bridge == null) {
            logger.error("BridgeHandler not found. Cannot handle command without bridge.");
            return;
        }
        if (ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.debug("Bridge is OFFLINE. Ignore command.");
            return;
        }
        if (apiClient == null) {
            logger.debug("No API client available.");
            return;
        }

        if (command instanceof RefreshType) {
            updateChannel(channelUID);
        }

    }

    @Override
    public void dispose() {
        if (serverSentEventListener != null && client != null) {
            client.unregisterEventListener(serverSentEventListener);
        }
    }

    @Override
    public void refreshClient(@NonNull HomeConnectApiClient apiClient) {
        HomeConnectApiClient oldClient = client;
        client = apiClient;

        if (refreshConnectionStatus()) {
            updateChannels();
        }

        // Only update client if new instance is passed
        if (!apiClient.equals(oldClient)) {
            serverSentEventListener = new ServerSentEventListener() {

                @Override
                public void onEvent(Event event) {
                    logger.debug("[{}] {}", getThingHaId(), event);

                    if (EVENT_DISCONNECTED.equals(event.getKey())) {
                        updateStatus(ThingStatus.OFFLINE);
                    } else {
                        if (!ThingStatus.ONLINE.equals(getThing().getStatus())) {
                            updateStatus(ThingStatus.ONLINE);
                        }
                    }

                    if (eventHandlers.containsKey(event.getKey())) {
                        eventHandlers.get(event.getKey()).handle(event);
                    } else {
                        logger.debug("[{}] Ignore event {}", getThingHaId(), event);
                    }
                }

                @Override
                public String haId() {
                    return getThingHaId();
                }

                @Override
                public void onReconnect() {
                }
            };

            try {
                apiClient.registerEventListener(serverSentEventListener);
                updateChannels();
            } catch (ConfigurationException | CommunicationException e) {
                logger.error("API communication problem!", e);
            }
        }
    }

    protected void registerEventHandler(String eventId, EventHandler eventHandler) {
        eventHandlers.put(eventId, eventHandler);
    }

    protected void unregisterEventHandler(String eventId) {
        eventHandlers.remove(eventId);
    }

    protected void registerChannelUpdateHandler(String channelId, ChannelUpdateHandler handler) {
        channelUpdateHandlers.put(channelId, handler);
    }

    protected void unregisterChannelUpdateHandler(String channelId) {
        channelUpdateHandlers.remove(channelId);
    }

    protected HomeConnectApiClient getClient() {
        return client;
    }

    protected Optional<Channel> getThingChannel(String channelId) {
        return Optional.ofNullable(getThing().getChannel(channelId));
    }

    /**
     * Update all channels via API.
     *
     */
    protected void updateChannels() {
        Bridge bridge = getBridge();
        if (bridge == null || ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.warn("BridgeHandler not found or offline. Stopping update of channels.");
            return;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debug("{} offline. Stopping update of channels.", getThing().getLabel());
            return;
        }

        List<Channel> channels = getThing().getChannels();
        for (Channel channel : channels) {
            updateChannel(channel.getUID());
        }
    }

    /**
     * Update Channel values via API.
     *
     * @param channelUID
     */
    protected void updateChannel(@NonNull ChannelUID channelUID) {
        HomeConnectApiClient apiClient = client;

        if (apiClient == null) {
            logger.error("Cannot update channel. No instance of api client found!");
            return;
        }

        Bridge bridge = getBridge();
        if (bridge == null || ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.warn("BridgeHandler not found or offline. Stopping update of channel {}.", channelUID);
            return;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debug("{} offline. Stopping update of channel {}.", getThing().getLabel(), channelUID);
            return;
        }

        if (channelUpdateHandlers.containsKey(channelUID.getId())) {
            try {
                channelUpdateHandlers.get(channelUID.getId()).handle(channelUID, apiClient);
            } catch (ConfigurationException | CommunicationException e) {
                logger.error("API communication problem while trying to update {}!", getThingHaId(), e);
            }
        }
    }

    /**
     * Map Home Connect key and value names to label.
     * e.g. Dishcare.Dishwasher.Program.Eco50 --> Eco50 or BSH.Common.EnumType.OperationState.DelayedStart --> Delayed
     * Start
     *
     * @param type
     * @return
     */
    protected String mapStringType(String type) {
        int index = type.lastIndexOf(".");
        if (index > 0 && type.length() > index) {
            String sub = type.substring(index + 1);
            StringBuilder sb = new StringBuilder();
            for (String word : sub.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")) {
                sb.append(" ");
                sb.append(word);
            }
            return sb.toString().trim();
        }
        return type;
    }

    /**
     * Map unit string (returned by home connect api) to Unit
     *
     * @param unit String eg. "°C"
     * @return Unit
     */
    protected Unit<Temperature> mapTemperature(String unit) {
        return !"°C".equalsIgnoreCase(unit) ? FAHRENHEIT : CELSIUS;
    }

    /**
     * Check bridge status and refresh connection status of thing accordingly.
     *
     * @return status has changed
     */
    protected boolean refreshConnectionStatus() {
        ThingStatus oldStatus = getThing().getStatus();
        if (client != null) {
            try {
                HomeAppliance homeAppliance = client.getHomeAppliance(getThingHaId());
                if (homeAppliance == null || !homeAppliance.isConnected()) {
                    updateStatus(ThingStatus.OFFLINE);
                } else {
                    updateStatus(ThingStatus.ONLINE);
                }
            } catch (ConfigurationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
            } catch (CommunicationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
        }

        return !oldStatus.equals(getThing().getStatus());
    }

    /**
     * Get home appliance id of Thing.
     *
     * @return home appliance id
     */
    protected String getThingHaId() {
        return getThing().getConfiguration().get(HA_ID).toString();
    }

    protected EventHandler defaultElapsedProgramTimeEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_ELAPSED_PROGRAM_TIME).ifPresent(
                    channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), SECOND)));
        };
    }

    protected EventHandler defaultPowerStateEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_POWER_ON.equals(event.getValue()) ? OnOffType.ON : OnOffType.OFF));
        };
    }

    protected EventHandler defaultDoorStateEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_DOOR_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_DOOR_OPEN.equals(event.getValue()) ? OpenClosedType.OPEN : OpenClosedType.CLOSED));
        };
    }

    protected EventHandler defaultOperationStateEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_OPERATION_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    event.getValue() == null ? UnDefType.NULL : new StringType(mapStringType(event.getValue()))));
        };
    }

    protected EventHandler defaultBooleanEventHandler(String channelId) {
        return event -> {
            getThingChannel(channelId).ifPresent(
                    channel -> updateState(channel.getUID(), event.getValueAsBoolean() ? OnOffType.ON : OnOffType.OFF));
        };
    }

    protected EventHandler defaultRemainingProgramTimeEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    event.getValueAsInt() == 0 ? UnDefType.NULL : new QuantityType<>(event.getValueAsInt(), SECOND)));
        };
    }

    protected EventHandler defaultSelectedProgramStateEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(mapStringType(event.getValue())));
            });
        };
    }

    protected EventHandler defaultProgramProgressEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE)
                    .ifPresent(channel -> updateState(channel.getUID(), event.getValueAsInt() == 100 ? UnDefType.NULL
                            : new QuantityType<>(event.getValueAsInt(), PERCENT)));
        };
    }

    protected ChannelUpdateHandler defaultDoorStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            Data data = client.getDoorState(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID,
                        STATE_DOOR_OPEN.equals(data.getValue()) ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        };
    }

    protected ChannelUpdateHandler defaultPowerStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            Data data = client.getPowerState(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, STATE_POWER_ON.equals(data.getValue()) ? OnOffType.ON : OnOffType.OFF);
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        };
    }

    protected ChannelUpdateHandler defaultOperationStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            Data data = client.getOperationState(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, new StringType(mapStringType(data.getValue())));
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        };
    }

    protected ChannelUpdateHandler defaultRemoteControlActiveStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            updateState(channelUID, client.isRemoteControlActive(getThingHaId()) ? OnOffType.ON : OnOffType.OFF);
        };
    }

    protected ChannelUpdateHandler defaultRemoteStartAllowanceChannelUpdateHandler() {
        return (channelUID, client) -> {
            updateState(channelUID, client.isRemoteControlStartAllowed(getThingHaId()) ? OnOffType.ON : OnOffType.OFF);
        };
    }

    protected ChannelUpdateHandler defaultSelectedProgramStateUpdateHandler() {
        return (channelUID, client) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(mapStringType(program.getKey())));
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        };
    }

    protected interface EventHandler {
        void handle(Event event);
    }

    protected interface ChannelUpdateHandler {
        void handle(ChannelUID channelUID, HomeConnectApiClient client)
                throws ConfigurationException, CommunicationException;
    }

}
