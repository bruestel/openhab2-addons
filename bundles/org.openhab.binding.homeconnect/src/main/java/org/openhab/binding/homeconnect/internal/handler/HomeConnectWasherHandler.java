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

import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectWasherHandler} is responsible for handling commands, which are
 * sent to one of the channels of a washing machine.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectWasherHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectWasherHandler.class);

    public HomeConnectWasherHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(thing, dynamicStateDescriptionProvider);
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());

        // register washer specific handlers
        handlers.put(CHANNEL_ACTIVE_PROGRAM_STATE, (channelUID, client) -> {
            Program program = client.getActiveProgram(getThingHaId());

            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(mapStringType(program.getKey())));
                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_REMAINING_PROGRAM_TIME:
                            getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE)
                                    .ifPresent(channel -> updateState(channel.getUID(),
                                            option.getValueAsInt() == 0 ? UnDefType.NULL
                                                    : new QuantityType<>(option.getValueAsInt(), SECOND)));
                            break;
                        case OPTION_PROGRAM_PROGRESS:
                            getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE)
                                    .ifPresent(channel -> updateState(channel.getUID(),
                                            option.getValueAsInt() == 100 ? UnDefType.NULL
                                                    : new QuantityType<>(option.getValueAsInt(), PERCENT)));
                            break;
                        case OPTION_WASHER_TEMPERATURE:
                            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_SPIN_SPEED:
                            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_IDOS_1_DOSING_LEVEL:
                            getThingChannel(CHANNEL_WASHER_IDOS1).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_IDOS_2_DOSING_LEVEL:
                            getThingChannel(CHANNEL_WASHER_IDOS2).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
                resetProgramStateChannels();
            }
        });
        handlers.put(CHANNEL_SELECTED_PROGRAM_STATE, (channelUID, client) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(program.getKey()));

                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_WASHER_TEMPERATURE:
                            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_SPIN_SPEED:
                            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_IDOS_1_DOSING_LEVEL:
                            getThingChannel(CHANNEL_WASHER_IDOS1).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_WASHER_IDOS_2_DOSING_LEVEL:
                            getThingChannel(CHANNEL_WASHER_IDOS2).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });
    }

    @Override
    protected void configureEventHandlers(ConcurrentHashMap<String, EventHandler> handlers) {
        // register default event handlers
        handlers.put(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        handlers.put(EVENT_REMOTE_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        handlers.put(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        handlers.put(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());

        // TODO do I need to fetch spin speed or temperature via api or is a event published on selected program change?
        handlers.put(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());

        // register washer specific event handlers
        handlers.put(EVENT_WASHER_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_SPIN_SPEED, event -> {
            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_IDOS_1_DOSING_LEVEL, event -> {
            getThingChannel(CHANNEL_WASHER_IDOS1).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_IDOS_2_DOSING_LEVEL, event -> {
            getThingChannel(CHANNEL_WASHER_IDOS2).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_OPERATION_STATE, event -> {
            defaultOperationStateEventHandler().handle(event);

            if (STATE_OPERATION_FINISHED.equals(event.getValue())) {
                getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE)
                        .ifPresent(c -> updateState(c.getUID(), new QuantityType<>(0, SECOND)));
                getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE)
                        .ifPresent(c -> updateState(c.getUID(), new QuantityType<>(100, PERCENT)));
            }

            if (STATE_OPERATION_RUN.equals(event.getValue())) {
                getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE)
                        .ifPresent(c -> updateState(c.getUID(), new QuantityType<>(0, PERCENT)));
                getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateChannel(c.getUID()));
            }

            if (STATE_OPERATION_READY.equals(event.getValue())) {
                resetProgramStateChannels();
            }
        });
        handlers.put(EVENT_ACTIVE_PROGRAM, event -> {
            defaultActiveProgramEventHandler().handle(event);

            if (event.getValue() == null) {
                resetProgramStateChannels();
            }
        });
    }

    @Override
    public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);

            if (logger.isDebugEnabled()) {
                logger.debug("{}: {}", channelUID, command);
            }

            try {
                // start or stop program
                if (command instanceof StringType && CHANNEL_BASIC_ACTIONS_STATE.equals(channelUID.getId())) {
                    updateState(channelUID, new StringType(""));

                    if ("start".equalsIgnoreCase(command.toFullString())) {
                        String program = getClient().getSelectedProgram(getThingHaId()).getKey();
                        getClient().startProgram(getThingHaId(), program);
                    } else {
                        getClient().stopProgram(getThingHaId());
                    }
                }

                // set selected program
                if (command instanceof StringType && CHANNEL_SELECTED_PROGRAM_STATE.equals(channelUID.getId())) {
                    getClient().setSelectedProgram(getThingHaId(), command.toFullString());
                }

                // set temperature option
                if (command instanceof StringType && CHANNEL_WASHER_TEMPERATURE.equals(channelUID.getId())) {
                    getClient().setProgramOptions(getThingHaId(), OPTION_WASHER_TEMPERATURE, command.toFullString(),
                            null, false);
                }

                // set spin speed option
                if (command instanceof StringType && CHANNEL_WASHER_SPIN_SPEED.equals(channelUID.getId())) {
                    getClient().setProgramOptions(getThingHaId(), OPTION_WASHER_SPIN_SPEED, command.toFullString(),
                            null, false);
                }

                // set iDos 1 option
                if (command instanceof StringType && CHANNEL_WASHER_IDOS1.equals(channelUID.getId())) {
                    getClient().setProgramOptions(getThingHaId(), OPTION_WASHER_IDOS_1_DOSING_LEVEL,
                            command.toFullString(), null, false);
                }

                // set iDos 2 option
                if (command instanceof StringType && CHANNEL_WASHER_IDOS2.equals(channelUID.getId())) {
                    getClient().setProgramOptions(getThingHaId(), OPTION_WASHER_IDOS_2_DOSING_LEVEL,
                            command.toFullString(), null, false);
                }

            } catch (CommunicationException e) {
                logger.warn("Could not handle command {}. API communication problem! error: {}", command.toFullString(),
                        e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectWasherHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }
}
