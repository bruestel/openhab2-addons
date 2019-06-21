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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
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

    public HomeConnectWasherHandler(Thing thing) {
        super(thing);
        // event handler
        registerEventHandler(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        registerEventHandler(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());
        registerEventHandler(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        registerEventHandler(EVENT_REMOTE_CONTROL_ACTIVE,
                defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        registerEventHandler(EVENT_ACTIVE_PROGRAM, event -> {
            getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(mapStringType(event.getValue())));

                // revert other channels
                if (event.getValue() == null) {
                    resetProgramStateChannels();
                } else {
                    // get progress etc. from API
                    updateChannel(channel.getUID());
                }
            });
        });
        registerEventHandler(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        registerEventHandler(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        registerEventHandler(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());
        registerEventHandler(EVENT_WASHER_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : mapWasherTemperature(event.getValue()));
            });
        });
        registerEventHandler(EVENT_WASHER_SPIN_SPEED, event -> {
            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : mapWasherSpinSpeed(event.getValue()));
            });
        });

        registerEventHandler(EVENT_DISCONNECTED, event -> {
            resetAllChannels();
        });
        registerEventHandler(EVENT_CONNECTED, event -> {
            // revert active program states
            resetProgramStateChannels();

            // refresh all channels
            updateChannels();
        });

        // register update handlers
        registerChannelUpdateHandler(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE,
                defaultRemoteControlActiveStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE,
                defaultRemoteStartAllowanceChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_ACTIVE_PROGRAM_STATE, (channelUID, client) -> {
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
                                    channel -> updateState(channel.getUID(), mapWasherTemperature(option.getValue())));
                            break;
                        case OPTION_WASHER_SPIN_SPEED:
                            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(
                                    channel -> updateState(channel.getUID(), mapWasherSpinSpeed(option.getValue())));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
                resetProgramStateChannels();
            }
        });
        registerChannelUpdateHandler(CHANNEL_SELECTED_PROGRAM_STATE, (channelUID, client) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(mapStringType(program.getKey())));
                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_WASHER_TEMPERATURE:
                            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(
                                    channel -> updateState(channel.getUID(), mapWasherTemperature(option.getValue())));
                            break;
                        case OPTION_WASHER_SPIN_SPEED:
                            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(
                                    channel -> updateState(channel.getUID(), mapWasherSpinSpeed(option.getValue())));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
                resetProgramStateChannels();
            }
        });
    }

    @Override
    public String toString() {
        return "HomeConnectWasherHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }

    private void resetAllChannels() {
        logger.debug("Resetting all channels");
        getThing().getChannels().forEach(channel -> {
            if (!CHANNEL_POWER_STATE.equals(channel.getUID().getId())) {
                updateState(channel.getUID(), UnDefType.NULL);
            }
        });
    }

    private State mapWasherTemperature(String value) {
        if (value.startsWith("LaundryCare.Washer.EnumType.Temperature.GC")) {
            return new StringType(value.replace("LaundryCare.Washer.EnumType.Temperature.GC", "") + "°C");
        }

        if (value.startsWith("LaundryCare.Washer.EnumType.Temperature.Ul")) {
            return new StringType(mapStringType(value.replace("LaundryCare.Washer.EnumType.Temperature.Ul", "")));
        }

        return new StringType(mapStringType(value));
    }

    private State mapWasherSpinSpeed(String value) {
        if (value.startsWith("LaundryCare.Washer.EnumType.SpinSpeed.RPM")) {
            return new StringType(value.replace("LaundryCare.Washer.EnumType.SpinSpeed.RPM", ""));
        }

        if (value.startsWith("LaundryCare.Washer.EnumType.SpinSpeed.Ul")) {
            return new StringType(value.replace("LaundryCare.Washer.EnumType.SpinSpeed.Ul", ""));
        }

        return new StringType(mapStringType(value));
    }
}
