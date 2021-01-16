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
package org.openhab.binding.homeconnect.internal.handler;

import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.SECOND;
import static org.eclipse.smarthome.core.thing.ThingStatus.OFFLINE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_ACTIVE_PROGRAM_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_DOOR_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_DURATION;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_ELAPSED_PROGRAM_TIME;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_OPERATION_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_POWER_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_PROGRAM_PROGRESS_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_REMAINING_PROGRAM_TIME_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_REMOTE_CONTROL_ACTIVE_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_REMOTE_START_ALLOWANCE_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_SELECTED_PROGRAM_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.CHANNEL_SETPOINT_TEMPERATURE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_ACTIVE_PROGRAM;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_DOOR_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_DURATION;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_ELAPSED_PROGRAM_TIME;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_OPERATION_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_OVEN_CAVITY_TEMPERATURE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_POWER_STATE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_PROGRAM_PROGRESS;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_REMAINING_PROGRAM_TIME;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_REMOTE_CONTROL_ACTIVE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_REMOTE_CONTROL_START_ALLOWED;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_SELECTED_PROGRAM;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.EVENT_SETPOINT_TEMPERATURE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.OPERATION_STATE_INACTIVE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.OPERATION_STATE_READY;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.OPTION_DURATION;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.OPTION_SETPOINT_TEMPERATURE;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.STATE_OPERATION_RUN;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.STATE_POWER_ON;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.STATE_POWER_STANDBY;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.measure.IncommensurableException;
import javax.measure.UnconvertibleException;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.ImperialUnits;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.exception.ApplianceOfflineException;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.type.HomeConnectDynamicStateDescriptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectOvenHandler} is responsible for handling commands, which are
 * sent to one of the channels of a oven.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectOvenHandler extends AbstractHomeConnectThingHandler {

    private static final List<String> INACTIVE_STATE = Arrays.asList(OPERATION_STATE_INACTIVE, OPERATION_STATE_READY);
    private static final int CAVITY_TEMPERATURE_SCHEDULER_INITIAL_DELAY = 30;
    private static final int CAVITY_TEMPERATURE_SCHEDULER_PERIOD = 90;

    private final Logger logger = LoggerFactory.getLogger(HomeConnectOvenHandler.class);
    private final ScheduledExecutorService scheduler;

    private @Nullable ScheduledFuture<?> cavityTemperatureFuture;
    private boolean manuallyUpdateCavityTemperature;

    public HomeConnectOvenHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(thing, dynamicStateDescriptionProvider);
        scheduler = ThreadPoolManager.getScheduledPool(getClass().getSimpleName());
        manuallyUpdateCavityTemperature = true;
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_POWER_STATE, defaultPowerStateChannelUpdateHandler());
        handlers.put(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());
        handlers.put(CHANNEL_SELECTED_PROGRAM_STATE, defaultSelectedProgramStateUpdateHandler());
        handlers.put(CHANNEL_ACTIVE_PROGRAM_STATE, defaultActiveProgramStateUpdateHandler());

        // register oven specific update handlers
        handlers.put(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE,
                (channelUID, cache) -> updateState(channelUID, cache.putIfAbsentAndGet(channelUID, () -> {
                    Optional<HomeConnectApiClient> apiClient = getApiClient();
                    if (apiClient.isPresent()) {
                        Data data = apiClient.get().getCurrentCavityTemperature(getThingHaId());
                        return new QuantityType<>(data.getValueAsInt(), mapTemperature(data.getUnit()));
                    }
                    return UnDefType.UNDEF;
                })));
        handlers.put(CHANNEL_SETPOINT_TEMPERATURE, (channelUID, cache) -> {
            Optional<Channel> channel = getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE);
            if (channel.isPresent()) {
                defaultSelectedProgramStateUpdateHandler().handle(channel.get().getUID(), cache);
            }
        });
        handlers.put(CHANNEL_DURATION, (channelUID, cache) -> {
            Optional<Channel> channel = getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE);
            if (channel.isPresent()) {
                defaultSelectedProgramStateUpdateHandler().handle(channel.get().getUID(), cache);
            }
        });
    }

    @Override
    protected void configureEventHandlers(ConcurrentHashMap<String, EventHandler> handlers) {
        // register default SSE event handlers
        handlers.put(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        handlers.put(EVENT_REMOTE_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        handlers.put(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());
        handlers.put(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        handlers.put(EVENT_PROGRAM_PROGRESS, defaultPercentEventHandler(CHANNEL_PROGRAM_PROGRESS_STATE));
        handlers.put(EVENT_ELAPSED_PROGRAM_TIME, defaultElapsedProgramTimeEventHandler());
        handlers.put(EVENT_ACTIVE_PROGRAM, defaultActiveProgramEventHandler());

        // register oven specific SSE event handlers
        handlers.put(EVENT_OPERATION_STATE, event -> {
            defaultOperationStateEventHandler().handle(event);
            if (STATE_OPERATION_RUN.equals(event.getValue())) {
                manuallyUpdateCavityTemperature = true;
            }
        });
        handlers.put(EVENT_POWER_STATE, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_POWER_ON.equals(event.getValue()) ? OnOffType.ON : OnOffType.OFF));

            if (STATE_POWER_ON.equals(event.getValue())) {
                updateChannels();
            } else {
                resetProgramStateChannels();
                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE)
                        .ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
                getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
                getThingChannel(CHANNEL_SETPOINT_TEMPERATURE).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
                getThingChannel(CHANNEL_DURATION).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
            }
        });

        handlers.put(EVENT_OVEN_CAVITY_TEMPERATURE, event -> {
            manuallyUpdateCavityTemperature = false;
            getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });

        handlers.put(EVENT_SETPOINT_TEMPERATURE,
                event -> getThingChannel(CHANNEL_SETPOINT_TEMPERATURE)
                        .ifPresent(channel -> updateState(channel.getUID(),
                                new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit())))));
        handlers.put(EVENT_DURATION, event -> getThingChannel(CHANNEL_DURATION).ifPresent(
                channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), SECOND))));
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);

            getApiClient().ifPresent(apiClient -> {
                try {
                    // turn coffee maker on and standby
                    if (command instanceof OnOffType && CHANNEL_POWER_STATE.equals(channelUID.getId())) {
                        apiClient.setPowerState(getThingHaId(),
                                OnOffType.ON.equals(command) ? STATE_POWER_ON : STATE_POWER_STANDBY);
                    }

                    @Nullable
                    String operationState = getOperationState();
                    if (operationState != null && INACTIVE_STATE.contains(operationState)) {
                        // set setpoint temperature
                        if (command instanceof QuantityType
                                && CHANNEL_SETPOINT_TEMPERATURE.equals(channelUID.getId())) {
                            @SuppressWarnings("unchecked")
                            QuantityType<Temperature> quantity = ((QuantityType<Temperature>) command);

                            try {
                                String value;
                                String unit;

                                if (quantity.getUnit().equals(SIUnits.CELSIUS)
                                        || quantity.getUnit().equals(ImperialUnits.FAHRENHEIT)) {
                                    unit = quantity.getUnit().toString();
                                    value = String.valueOf(quantity.intValue());
                                } else {
                                    logger.debug(
                                            "Converting target setpoint temperature from {}{} to °C value. haId={}",
                                            quantity.intValue(), quantity.getUnit().toString(), getThingHaId());
                                    unit = "°C";
                                    value = String.valueOf(quantity.getUnit().getConverterToAny(SIUnits.CELSIUS)
                                            .convert(quantity).intValue());
                                    logger.debug("{}{}", value, unit);
                                }

                                logger.debug("Set setpoint temperature to {} {}. haId={}", value, unit, getThingHaId());
                                apiClient.setProgramOptions(getThingHaId(), OPTION_SETPOINT_TEMPERATURE, value, unit,
                                        true, false);
                            } catch (IncommensurableException | UnconvertibleException e) {
                                logger.warn("Could not set setpoint! haId={}, error={}", getThingHaId(),
                                        e.getMessage());
                            }
                        }

                        // set duration
                        if (command instanceof QuantityType && CHANNEL_DURATION.equals(channelUID.getId())) {
                            @SuppressWarnings("unchecked")
                            QuantityType<Time> quantity = ((QuantityType<Time>) command);

                            try {
                                String value = String.valueOf(
                                        quantity.getUnit().getConverterToAny(SECOND).convert(quantity).intValue());
                                logger.debug("Set duration to {} seconds. haId={}", value, getThingHaId());

                                apiClient.setProgramOptions(getThingHaId(), OPTION_DURATION, value, "seconds", true,
                                        false);
                            } catch (IncommensurableException | UnconvertibleException e) {
                                logger.warn("Could not set duration! haId={}, error={}", getThingHaId(),
                                        e.getMessage());
                            }
                        }
                    } else {
                        logger.debug("Device can not handle command {} in current operation state ({}). haId={}",
                                command, operationState, getThingHaId());
                    }
                } catch (ApplianceOfflineException e) {
                    logger.debug("Could not handle command {}. Appliance offline. thing={}, haId={}, error={}",
                            command.toFullString(), getThingLabel(), getThingHaId(), e.getMessage());
                    updateStatus(OFFLINE);
                    resetChannelsOnOfflineEvent();
                    resetProgramStateChannels();
                } catch (CommunicationException e) {
                    logger.debug("Could not handle command {}. API communication problem! thing={}, haId={}, error={}",
                            command.toFullString(), getThingLabel(), getThingHaId(), e.getMessage());
                } catch (AuthorizationException e) {
                    logger.debug("Could not handle command {}. Authorization problem! thing={}, haId={}, error={}",
                            command.toFullString(), getThingLabel(), getThingHaId(), e.getMessage());
                    handleAuthenticationError(e);
                }
            });
        }
    }

    @Override
    public void initialize() {
        super.initialize();
        cavityTemperatureFuture = scheduler.scheduleWithFixedDelay(() -> {
            @Nullable
            String operationState = getOperationState();
            boolean manuallyUpdateCavityTemperature = this.manuallyUpdateCavityTemperature;

            if (STATE_OPERATION_RUN.equals(operationState)) {
                getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE).ifPresent(c -> {
                    if (manuallyUpdateCavityTemperature) {
                        logger.debug("Update cavity temperature manually via API. haId={}", getThingHaId());
                        updateChannel(c.getUID());
                    } else {
                        logger.debug("Update cavity temperature via SSE, don't need to fetch manually. haId={}",
                                getThingHaId());
                    }
                });
            }
        }, CAVITY_TEMPERATURE_SCHEDULER_INITIAL_DELAY, CAVITY_TEMPERATURE_SCHEDULER_PERIOD, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        super.dispose();
        @Nullable
        ScheduledFuture<?> cavityTemperatureFuture = this.cavityTemperatureFuture;
        if (cavityTemperatureFuture != null) {
            cavityTemperatureFuture.cancel(true);
        }
    }

    @Override
    public String toString() {
        return "HomeConnectOvenHandler [haId: " + getThingHaId() + "]";
    }

    @Override
    protected void resetProgramStateChannels() {
        super.resetProgramStateChannels();
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
        getThingChannel(CHANNEL_ELAPSED_PROGRAM_TIME).ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
        getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE)
                .ifPresent(c -> updateState(c.getUID(), UnDefType.UNDEF));
    }
}
