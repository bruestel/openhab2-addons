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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
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
import org.eclipse.smarthome.core.thing.binding.BridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.StateDescription;
import org.eclipse.smarthome.core.types.StateDescriptionFragmentBuilder;
import org.eclipse.smarthome.core.types.StateOption;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
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

    @Nullable
    private String operationState;

    private final ConcurrentHashMap<String, EventHandler> eventHandlers;
    private final ConcurrentHashMap<String, ChannelUpdateHandler> channelUpdateHandlers;
    private final HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    public AbstractHomeConnectThingHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(thing);
        eventHandlers = new ConcurrentHashMap<>();
        channelUpdateHandlers = new ConcurrentHashMap<>();
        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;

        configureEventHandlers(eventHandlers);
        configureChannelUpdateHandlers(channelUpdateHandlers);
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
                } catch (CommunicationException e) {
                    logger.error("API communication problem!", e);
                }
            }

            updateChannels();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (isThingReadyToHandleCommand() && command instanceof RefreshType) {
            updateChannel(channelUID);
        }
    }

    @Override
    public void dispose() {
        HomeConnectApiClient client = this.client;
        if (serverSentEventListener != null && client != null) {
            client.unregisterEventListener(serverSentEventListener);
        }
    }

    @Override
    public void refreshApiClient(@NonNull HomeConnectApiClient apiClient) {
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
                        logger.info("Received offline event. Set {} to offline.", getThing().getLabel());
                        updateStatus(ThingStatus.OFFLINE);
                    } else {
                        if (!ThingStatus.ONLINE.equals(getThing().getStatus())) {
                            updateStatus(ThingStatus.ONLINE);
                            logger.info("Set {} to online.", getThing().getLabel());
                            updateChannels();
                        }
                    }

                    if (EVENT_OPERATION_STATE.contentEquals(event.getKey())) {
                        operationState = event.getValue() == null ? null : event.getValue();
                    }

                    if (eventHandlers.containsKey(event.getKey())) {
                        eventHandlers.get(event.getKey()).handle(event);
                    } else {
                        logger.debug("[{}] No event handler registered for event {}. Ignore event.", getThingHaId(),
                                event);
                    }
                }

                @Override
                public String haId() {
                    return getThingHaId();
                }

                @Override
                public void onReconnect() {
                }

                @Override
                public void onReconnectFailed() {
                    final ServerSentEventListener ssel = this;
                    apiClient.unregisterEventListener(ssel);
                    TimerTask reStartTask = new TimerTask() {
                        @Override
                        public void run() {
                            logger.debug("Trying to reconnect to SSE endpoint.");
                            refreshConnectionStatus();
                            try {
                                apiClient.registerEventListener(ssel);
                            } catch (CommunicationException e) {
                                logger.error("Home Connect service is not reachable or a problem occurred! {}",
                                        e.getMessage());
                            }
                        }
                    };
                    new Timer("Restart").schedule(reStartTask, 10000L);
                }
            };

            try {
                apiClient.registerEventListener(serverSentEventListener);
                updateChannels();
            } catch (CommunicationException e) {
                logger.error("Home Connect service is not reachable or a problem occurred! {}", e.getMessage());
            }
        }

        // update available selectable programs (dynamic program list)
        if (!(this instanceof HomeConnectFridgeFreezerHandler)) {
            try {
                ArrayList<StateOption> stateOptions = new ArrayList<>();
                apiClient.getPrograms(getThingHaId()).stream().filter(p -> p.isAvailable()).forEach(p -> {
                    stateOptions.add(new StateOption(p.getKey(), mapStringType(p.getKey())));
                });

                StateDescription stateDescription = StateDescriptionFragmentBuilder.create().withPattern("%s")
                        .withReadOnly(stateOptions.isEmpty()).withOptions(stateOptions).build().toStateDescription();

                if (stateDescription != null) {
                    dynamicStateDescriptionProvider.putStateDescriptions(
                            getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).get().getUID().getAsString(),
                            stateDescription);
                }
            } catch (CommunicationException e) {
                logger.error("Could not fetch available programs. {}", e.getMessage());
            }
        }
    }

    protected boolean isThingReadyToHandleCommand() {
        Bridge bridge = getBridge();
        HomeConnectApiClient apiClient = client;
        if (bridge == null) {
            logger.warn("BridgeHandler not found. Cannot handle command without bridge.");
            return false;
        }
        if (ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.debug("Bridge is OFFLINE. Ignore command.");
            return false;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debug("{} is OFFLINE. Ignore command.", getThing().getLabel());
            return false;
        }

        if (apiClient == null) {
            logger.debug("No API client available.");
            return false;
        }

        return true;
    }

    protected HomeConnectApiClient getClient() {
        return client;
    }

    protected Optional<Channel> getThingChannel(String channelId) {
        return Optional.ofNullable(getThing().getChannel(channelId));
    }

    protected abstract void configureChannelUpdateHandlers(
            final @NonNull ConcurrentHashMap<String, ChannelUpdateHandler> handlers);

    protected abstract void configureEventHandlers(final @NonNull ConcurrentHashMap<String, EventHandler> handlers);

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
            } catch (CommunicationException e) {
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
        HomeConnectApiClient client = this.client;

        if (client != null) {
            try {
                HomeAppliance homeAppliance = client.getHomeAppliance(getThingHaId());
                if (homeAppliance == null || !homeAppliance.isConnected()) {
                    updateStatus(ThingStatus.OFFLINE);
                } else {
                    updateStatus(ThingStatus.ONLINE);
                }
            } catch (CommunicationException | RuntimeException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Home Connect service is not reachable or a problem occurred! (" + e.getMessage() + ").");

                // inform bridge
                Bridge bridge = getBridge();
                if (bridge != null && ThingStatus.ONLINE.equals(bridge.getStatus())) {
                    BridgeHandler bridgeHandler = bridge.getHandler();
                    if (bridgeHandler != null && bridgeHandler instanceof HomeConnectBridgeHandler) {
                        HomeConnectBridgeHandler homeConnectBridgeHandler = (HomeConnectBridgeHandler) bridgeHandler;
                        homeConnectBridgeHandler.reInitialize();
                    }
                }
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

    protected String getCurrentOperationState() {
        return operationState;
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

    protected EventHandler defaultActiveProgramEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(mapStringType(event.getValue())));
            });
        };
    }

    protected EventHandler defaultEventPresentStateEventHandler(String channelId) {
        return event -> {
            getThingChannel(channelId).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_EVENT_PRESENT_STATE_OFF.equals(event.getValue()) ? OnOffType.OFF : OnOffType.ON));
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
            getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(
                    channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), SECOND)));
        };
    }

    protected EventHandler defaultSelectedProgramStateEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        };
    }

    protected EventHandler defaultProgramProgressEventHandler() {
        return event -> {
            getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(
                    channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), PERCENT)));
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

    protected ChannelUpdateHandler defaultNoOpUpdateHandler() {
        return (channelUID, client) -> {
            updateState(channelUID, UnDefType.NULL);
        };
    }

    protected ChannelUpdateHandler defaultOperationStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            Data data = client.getOperationState(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, new StringType(mapStringType(data.getValue())));
                operationState = data.getValue();
            } else {
                updateState(channelUID, UnDefType.NULL);
                operationState = null;
            }
        };
    }

    protected ChannelUpdateHandler defaultRemoteControlActiveStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            updateState(channelUID, client.isRemoteControlActive(getThingHaId()) ? OnOffType.ON : OnOffType.OFF);
        };
    }

    protected ChannelUpdateHandler defaultLocalControlActiveStateChannelUpdateHandler() {
        return (channelUID, client) -> {
            updateState(channelUID, client.isLocalControlActive(getThingHaId()) ? OnOffType.ON : OnOffType.OFF);
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
                updateState(channelUID, new StringType(program.getKey()));
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        };
    }

    protected interface EventHandler {
        void handle(Event event);
    }

    protected interface ChannelUpdateHandler {
        void handle(ChannelUID channelUID, HomeConnectApiClient client) throws CommunicationException;
    }

}
