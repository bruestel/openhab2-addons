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

import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.SECOND;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.concurrent.ConcurrentHashMap;

import javax.measure.IncommensurableException;
import javax.measure.UnconvertibleException;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.ImperialUnits;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.openhab.binding.homeconnect.internal.logger.EmbeddedLoggingService;
import org.openhab.binding.homeconnect.internal.logger.Logger;
import org.openhab.binding.homeconnect.internal.type.HomeConnectDynamicStateDescriptionProvider;

import jersey.repackaged.com.google.common.collect.ImmutableList;

/**
 * The {@link HomeConnectOvenHandler} is responsible for handling commands, which are
 * sent to one of the channels of a oven.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectOvenHandler extends AbstractHomeConnectThingHandler {

    private static final ImmutableList<String> INACTIVE_STATE = ImmutableList.of(OPERATION_STATE_INACTIVE,
            OPERATION_STATE_READY);

    private final Logger logger;

    public HomeConnectOvenHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            EmbeddedLoggingService loggingService) {
        super(thing, dynamicStateDescriptionProvider, loggingService);
        logger = loggingService.getLogger(HomeConnectOvenHandler.class);
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
        handlers.put(CHANNEL_SETPOINT_TEMPERATURE, (channelUID, client, cache) -> {
            // only update channel if channel CHANNEL_SELECTED_PROGRAM_STATE is not there
            if (!getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).isPresent()) {
                cachePutIfAbsentAndGet(channelUID, cache, () -> {
                    Program program = client.getSelectedProgram(getThingHaId());
                    if (program != null && program.getKey() != null) {
                        processProgramOptions(program.getOptions());
                    }
                    return UnDefType.NULL;
                });
            }
        });
        handlers.put(CHANNEL_DURATION, (channelUID, client, cache) -> {
            // only update channel if channel CHANNEL_SELECTED_PROGRAM_STATE and CHANNEL_SETPOINT_TEMPERATURE are not
            // there
            if (!getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).isPresent()
                    && !getThingChannel(CHANNEL_SETPOINT_TEMPERATURE).isPresent()) {
                cachePutIfAbsentAndGet(channelUID, cache, () -> {
                    Program program = client.getSelectedProgram(getThingHaId());
                    if (program != null && program.getKey() != null) {
                        processProgramOptions(program.getOptions());
                    }
                    return UnDefType.NULL;
                });
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
        handlers.put(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        handlers.put(EVENT_ELAPSED_PROGRAM_TIME, defaultElapsedProgramTimeEventHandler());
        handlers.put(EVENT_ACTIVE_PROGRAM, defaultActiveProgramEventHandler());
        handlers.put(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());

        // register oven specific SSE event handlers
        handlers.put(EVENT_POWER_STATE, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_POWER_ON.equals(event.getValue()) ? OnOffType.ON : OnOffType.OFF));

            if (STATE_POWER_ON.equals(event.getValue())) {
                updateChannels();
            } else {
                resetProgramStateChannels();
                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
                getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
                getThingChannel(CHANNEL_SETPOINT_TEMPERATURE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
                getThingChannel(CHANNEL_DURATION).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
            }
        });
        handlers.put(EVENT_OVEN_CAVITY_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });

        handlers.put(EVENT_SETPOINT_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_SETPOINT_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });
        handlers.put(EVENT_DURATION, event -> {
            getThingChannel(CHANNEL_DURATION).ifPresent(
                    channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), SECOND)));
        });
    }

    @Override
    public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);

            try {
                // turn coffee maker on and standby
                if (command instanceof OnOffType && CHANNEL_POWER_STATE.equals(channelUID.getId())) {
                    getApiClient().setPowerState(getThingHaId(),
                            OnOffType.ON.equals(command) ? STATE_POWER_ON : STATE_POWER_STANDBY);
                }

                String operationState = getOperationState();
                if (operationState != null && INACTIVE_STATE.contains(operationState)) {
                    // set setpoint temperature
                    if (command instanceof QuantityType && CHANNEL_SETPOINT_TEMPERATURE.equals(channelUID.getId())) {
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
                                logger.infoWithHaId(getThingHaId(),
                                        "Converting target setpoint temperature from {}{} to °C value.",
                                        quantity.intValue(), quantity.getUnit().toString());
                                unit = "°C";
                                value = String.valueOf(quantity.getUnit().getConverterToAny(SIUnits.CELSIUS)
                                        .convert(quantity).intValue());
                                logger.infoWithHaId(getThingHaId(), "{}{}", value, unit);
                            }

                            logger.debugWithHaId(getThingHaId(), "Set setpoint temperature to {} {}.", value, unit);
                            getApiClient().setProgramOptions(getThingHaId(), OPTION_SETPOINT_TEMPERATURE, value, unit,
                                    true, false);

                        } catch (IncommensurableException | UnconvertibleException e) {
                            logger.errorWithHaId(getThingHaId(), "Could not set setpoint! {}", e.getMessage());
                        }
                    }

                    // set duration
                    if (command instanceof QuantityType && CHANNEL_DURATION.equals(channelUID.getId())) {
                        @SuppressWarnings("unchecked")
                        QuantityType<Time> quantity = ((QuantityType<Time>) command);

                        try {
                            String value = String
                                    .valueOf(quantity.getUnit().getConverterToAny(SECOND).convert(quantity).intValue());
                            logger.debugWithHaId(getThingHaId(), "Set duration to {} seconds.", value);

                            getApiClient().setProgramOptions(getThingHaId(), OPTION_DURATION, value, "seconds", true,
                                    false);
                        } catch (IncommensurableException | UnconvertibleException e) {
                            logger.errorWithHaId(getThingHaId(), "Could not set duration! error: {}", e.getMessage());
                        }
                    }
                } else {
                    logger.debugWithHaId(getThingHaId(),
                            "Device can not handle command {} in current operation state ({}).", command,
                            operationState);
                }
            } catch (CommunicationException e) {
                logger.warnWithHaId(getThingHaId(), "Could not handle command {}. API communication problem! error: {}",
                        command.toFullString(), e.getMessage());
            } catch (AuthorizationException e) {
                logger.warnWithHaId(getThingHaId(), "Could not handle command {}. Authorization problem! error: {}",
                        command.toFullString(), e.getMessage());

                handleAuthenticationError(e);
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectOvenHandler [haId: " + getThingHaId() + "]";
    }

    @Override
    protected void resetProgramStateChannels() {
        super.resetProgramStateChannels();
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ELAPSED_PROGRAM_TIME).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE)
                .ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }
}
