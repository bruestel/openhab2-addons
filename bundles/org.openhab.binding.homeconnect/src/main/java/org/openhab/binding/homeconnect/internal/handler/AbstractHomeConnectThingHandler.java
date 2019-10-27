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
package org.openhab.binding.homeconnect.internal.handler;

import static org.eclipse.smarthome.core.library.unit.ImperialUnits.FAHRENHEIT;
import static org.eclipse.smarthome.core.library.unit.SIUnits.CELSIUS;
import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthException;
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
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.BridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.StateDescription;
import org.eclipse.smarthome.core.types.StateDescriptionFragmentBuilder;
import org.eclipse.smarthome.core.types.StateOption;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.openhab.binding.homeconnect.internal.logger.EmbeddedLoggingService;
import org.openhab.binding.homeconnect.internal.logger.Logger;

/**
 * The {@link AbstractHomeConnectThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jonas Brüstel - Initial contribution
 */
public abstract class AbstractHomeConnectThingHandler extends BaseThingHandler implements ServerSentEventListener {

    private @Nullable String operationState;

    private final ConcurrentHashMap<String, EventHandler> eventHandlers;
    private final ConcurrentHashMap<String, ChannelUpdateHandler> channelUpdateHandlers;
    private final HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider;
    private final Logger logger;

    public AbstractHomeConnectThingHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            EmbeddedLoggingService loggingService) {
        super(thing);
        eventHandlers = new ConcurrentHashMap<>();
        channelUpdateHandlers = new ConcurrentHashMap<>();
        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
        logger = loggingService.getLogger(AbstractHomeConnectThingHandler.class);

        configureEventHandlers(eventHandlers);
        configureChannelUpdateHandlers(channelUpdateHandlers);
    }

    @Override
    public void initialize() {
        logger.debugWithHaId(getThingHaId(), "Initialize thing handler ({}).", getThingLabel());

        Bridge bridge = getBridge();
        if (bridge != null && ThingStatus.ONLINE.equals(bridge.getStatus())) {
            refreshThingStatus();
            updateSelectedProgramStateDescription();
            updateChannels();
            BridgeHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler != null && bridgeHandler instanceof HomeConnectBridgeHandler) {
                HomeConnectBridgeHandler homeConnectBridgeHandler = (HomeConnectBridgeHandler) bridgeHandler;
                homeConnectBridgeHandler.registerServerSentEventListener(this);
            }
        } else {
            logger.debugWithHaId(getThingHaId(), "Bridge is not online ({}), skip initialization of thing handler.",
                    getThingLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void dispose() {
        Bridge bridge = getBridge();
        if (bridge != null) {
            BridgeHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler != null && bridgeHandler instanceof HomeConnectBridgeHandler) {
                HomeConnectBridgeHandler homeConnectBridgeHandler = (HomeConnectBridgeHandler) bridgeHandler;
                homeConnectBridgeHandler.unregisterServerSentEventListener(this);
            }
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);
        logger.debugWithHaId(getThingHaId(), "Bridge status changed to {} ({}).", bridgeStatusInfo, getThingLabel());

        dispose();
        initialize();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (isThingReadyToHandleCommand()) {
            logger.debugWithHaId(getThingHaId(), "Handle \"{}\" command ({}).", command, channelUID);

            if (command instanceof RefreshType) {
                updateChannel(channelUID);
            }
        }
    }

    @Override
    public void onEvent(@NonNull Event event) {
        logger.debugWithHaId(getThingHaId(), "{}", event);

        if (EVENT_DISCONNECTED.equals(event.getKey())) {
            logger.infoWithHaId(getThingHaId(), "Received offline event. Set {} to offline.", getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE);
        } else {
            if (!ThingStatus.ONLINE.equals(getThing().getStatus())) {
                updateStatus(ThingStatus.ONLINE);
                logger.infoWithHaId(getThingHaId(), "Set {} to online.", getThing().getLabel());
                updateChannels();
            }
        }

        if (EVENT_OPERATION_STATE.contentEquals(event.getKey())) {
            operationState = event.getValue() == null ? null : event.getValue();
        }

        if (eventHandlers.containsKey(event.getKey())) {
            eventHandlers.get(event.getKey()).handle(event);
        } else {
            logger.debugWithHaId(getThingHaId(), "No event handler registered for event {}. Ignore event.", event);
        }
    }

    @Override
    public void onReconnectFailed() {
        logger.errorWithHaId(getThingHaId(), "SSE connection was closed due to authentication problems!");
        handleAuthenticationError(new AuthorizationException("SSE connection was killed!"));
    }

    /**
     * Get {@link HomeConnectApiClient}.
     *
     * @return client instance
     */
    protected HomeConnectApiClient getApiClient() {
        HomeConnectApiClient apiClient = null;
        Bridge bridge = getBridge();
        if (bridge != null && ThingStatus.ONLINE.equals(bridge.getStatus())) {
            BridgeHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler != null && bridgeHandler instanceof HomeConnectBridgeHandler) {
                HomeConnectBridgeHandler homeConnectBridgeHandler = (HomeConnectBridgeHandler) bridgeHandler;
                apiClient = homeConnectBridgeHandler.getApiClient();
            }
        }

        return apiClient;
    }

    /**
     * Update state description of selected program (Fetch programs via API).
     */
    protected void updateSelectedProgramStateDescription() {
        Bridge bridge = getBridge();
        if (bridge == null || ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            return;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            return;
        }

        // exclude fridge/freezer as they don't have programs
        if (!(this instanceof HomeConnectFridgeFreezerHandler)) {
            HomeConnectApiClient apiClient = getApiClient();
            if (apiClient != null) {
                try {
                    ArrayList<StateOption> stateOptions = new ArrayList<>();
                    apiClient.getPrograms(getThingHaId()).stream().forEach(p -> {
                        stateOptions.add(new StateOption(p.getKey(), mapStringType(p.getKey())));
                    });

                    StateDescription stateDescription = StateDescriptionFragmentBuilder.create().withPattern("%s")
                            .withReadOnly(stateOptions.isEmpty()).withOptions(stateOptions).build()
                            .toStateDescription();

                    if (stateDescription != null) {
                        dynamicStateDescriptionProvider.putStateDescriptions(
                                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).get().getUID().getAsString(),
                                stateDescription);
                    }
                } catch (CommunicationException | AuthorizationException e) {
                    logger.errorWithHaId(getThingHaId(), "Could not fetch available programs. {}", e.getMessage());
                    removeSelectedProgramStateDescription();
                }
            } else {
                removeSelectedProgramStateDescription();
            }
        }
    }

    /**
     * Remove state description of selected program.
     */
    protected void removeSelectedProgramStateDescription() {
        // exclude fridge/freezer as they don't have programs
        if (!(this instanceof HomeConnectFridgeFreezerHandler)) {
            dynamicStateDescriptionProvider.removeStateDescriptions(
                    getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).get().getUID().getAsString());
        }
    }

    /**
     * Is thing ready to process commands. If bridge or thing itself is offline commands will be ignored.
     *
     * @return
     */
    protected boolean isThingReadyToHandleCommand() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            logger.warnWithHaId(getThingHaId(), "BridgeHandler not found. Cannot handle command without bridge.");
            return false;
        }
        if (ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.debugWithHaId(getThingHaId(), "Bridge is OFFLINE. Ignore command.");
            return false;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debugWithHaId(getThingHaId(), "{} is OFFLINE. Ignore command.", getThing().getLabel());
            return false;
        }

        return true;
    }

    /**
     * Get thing channel by given channel id.
     *
     * @param channelId
     * @return
     */
    protected Optional<Channel> getThingChannel(String channelId) {
        return Optional.ofNullable(getThing().getChannel(channelId));
    }

    /**
     * Configure channel update handlers. Classes which extend {@link AbstractHomeConnectThingHandler} must implement
     * this class and add handlers.
     *
     * @param handlers channel update handlers
     */
    protected abstract void configureChannelUpdateHandlers(
            final @NonNull ConcurrentHashMap<String, ChannelUpdateHandler> handlers);

    /**
     * Configure event handlers. Classes which extend {@link AbstractHomeConnectThingHandler} must implement
     * this class and add handlers.
     *
     * @param handlers Server-Sent-Event handlers
     */
    protected abstract void configureEventHandlers(final @NonNull ConcurrentHashMap<String, EventHandler> handlers);

    /**
     * Update all channels via API.
     *
     */
    protected void updateChannels() {
        Bridge bridge = getBridge();
        if (bridge == null || ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.warnWithHaId(getThingHaId(), "BridgeHandler not found or offline. Stopping update of channels.");
            return;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debugWithHaId(getThingHaId(), "{} offline. Stopping update of channels.", getThing().getLabel());
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
        HomeConnectApiClient apiClient = getApiClient();

        if (apiClient == null) {
            logger.errorWithHaId(getThingHaId(), "Cannot update channel. No instance of api client found!");
            return;
        }

        Bridge bridge = getBridge();
        if (bridge == null || ThingStatus.OFFLINE.equals(bridge.getStatus())) {
            logger.warnWithHaId(getThingHaId(), "BridgeHandler not found or offline. Stopping update of channel {}.",
                    channelUID);
            return;
        }

        if (ThingStatus.OFFLINE.equals(getThing().getStatus())) {
            logger.debugWithHaId(getThingHaId(), "{} offline. Stopping update of channel {}.", getThing().getLabel(),
                    channelUID);
            return;
        }

        if (channelUpdateHandlers.containsKey(channelUID.getId())) {
            try {
                channelUpdateHandlers.get(channelUID.getId()).handle(channelUID, apiClient);
            } catch (CommunicationException | AuthorizationException e) {
                logger.errorWithHaId(getThingHaId(), "API communication problem while trying to update!", e);
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
    protected void refreshThingStatus() {
        HomeConnectApiClient client = getApiClient();

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
            } catch (AuthorizationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Home Connect service is not reachable or a problem occurred! (" + e.getMessage() + ").");

                handleAuthenticationError(e);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
        }
    }

    /**
     * Get home appliance id of Thing.
     *
     * @return home appliance id
     */
    protected String getThingHaId() {
        return getThing().getConfiguration().get(HA_ID).toString();
    }

    /**
     * Returns the human readable label for this thing.
     *
     * @return the human readable label
     */
    protected String getThingLabel() {
        return getThing().getLabel();
    }

    /**
     * Handle authentication exception.
     */
    protected void handleAuthenticationError(AuthorizationException exception) {
        logger.infoWithHaId(getThingHaId(),
                "Thing handler got authentication exception --> clear credential storage ({})", exception.getMessage());
        Bridge bridge = getBridge();
        if (bridge != null) {
            BridgeHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler != null && bridgeHandler instanceof HomeConnectBridgeHandler) {
                HomeConnectBridgeHandler homeConnectBridgeHandler = (HomeConnectBridgeHandler) bridgeHandler;

                try {
                    homeConnectBridgeHandler.getOAuthClientService().remove();
                } catch (OAuthException e) {
                    logger.errorWithHaId(getThingHaId(), "Could not clear oAuth storage!", e);
                }
                homeConnectBridgeHandler.dispose();
                homeConnectBridgeHandler.initialize();
            }
        }
    }

    /**
     * Get operation state of device.
     *
     * @return
     */
    protected String getOperationState() {
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
        void handle(ChannelUID channelUID, HomeConnectApiClient client)
                throws CommunicationException, AuthorizationException;
    }

}
