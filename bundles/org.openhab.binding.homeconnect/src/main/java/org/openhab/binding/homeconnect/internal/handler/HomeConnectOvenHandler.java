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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
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

    private final Logger logger = LoggerFactory.getLogger(HomeConnectOvenHandler.class);

    public HomeConnectOvenHandler(Thing thing) {
        super(thing);

        // register default SSE event handlers
        registerEventHandler(EVENT_ELAPSED_PROGRAM_TIME, defaultElapsedProgramTimeEventHandler());
        registerEventHandler(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        registerEventHandler(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());
        registerEventHandler(EVENT_REMOTE_CONTROL_ACTIVE,
                defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        registerEventHandler(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        registerEventHandler(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        registerEventHandler(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        registerEventHandler(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());

        // register oven specific SSE event handlers
        registerEventHandler(EVENT_OVEN_CAVITY_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });
        registerEventHandler(EVENT_DISCONNECTED, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(), OnOffType.OFF));
            resetAllChannels();
        });
        registerEventHandler(EVENT_CONNECTED, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(), OnOffType.ON));

            // revert active program states
            resetProgramStateChannels();

            // refresh all channels
            updateChannels();
        });
        registerEventHandler(EVENT_POWER_STATE, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_POWER_ON.equals(event.getValue()) ? OnOffType.ON : OnOffType.OFF));

            if (STATE_POWER_ON.equals(event.getValue())) {
                // revert active program states
                resetProgramStateChannels();

                // refresh all channels
                updateChannels();
            } else {
                resetAllChannels();
            }

        });
        registerEventHandler(EVENT_ACTIVE_PROGRAM, event -> {
            getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(mapStringType(event.getValue())));

                // revert other channels
                if (event.getValue() == null) {
                    resetProgramStateChannels();
                }
            });
        });
        registerEventHandler(EVENT_SETPOINT_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_SETPOINT_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });
        registerEventHandler(EVENT_DURATION, event -> {
            getThingChannel(CHANNEL_DURATION).ifPresent(
                    channel -> updateState(channel.getUID(), new QuantityType<>(event.getValueAsInt(), SECOND)));
        });

        // register default update handlers
        registerChannelUpdateHandler(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_POWER_STATE, defaultPowerStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE,
                defaultRemoteControlActiveStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE,
                defaultRemoteStartAllowanceChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_SELECTED_PROGRAM_STATE, defaultSelectedProgramStateUpdateHandler());

        // register oven specific update handlers
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
                        case OPTION_ELAPSED_PROGRAM_TIME:
                            getThingChannel(CHANNEL_ELAPSED_PROGRAM_TIME)
                                    .ifPresent(channel -> updateState(channel.getUID(),
                                            new QuantityType<>(option.getValueAsInt(), SECOND)));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
                resetProgramStateChannels();
            }
        });
        registerChannelUpdateHandler(CHANNEL_SETPOINT_TEMPERATURE, (channelUID, client) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                Optional<Option> option = program.getOptions().stream()
                        .filter(o -> o.getKey().equals(OPTION_SETPOINT_TEMPERATURE)).findFirst();
                if (option.isPresent()) {
                    updateState(channelUID,
                            new QuantityType<>(option.get().getValueAsInt(), mapTemperature(option.get().getUnit())));
                } else {
                    updateState(channelUID, UnDefType.NULL);
                }
            }
        });
        registerChannelUpdateHandler(CHANNEL_DURATION, (channelUID, client) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                Optional<Option> option = program.getOptions().stream().filter(o -> o.getKey().equals(OPTION_DURATION))
                        .findFirst();
                if (option.isPresent()) {
                    updateState(channelUID, new QuantityType<>(option.get().getValueAsInt(), SECOND));
                } else {
                    updateState(channelUID, UnDefType.NULL);
                }
            }
        });
    }

    @Override
    public String toString() {
        return "HomeConnectOvenHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ELAPSED_PROGRAM_TIME).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }

    private void resetAllChannels() {
        logger.debug("Resetting all channels");
        getThing().getChannels().forEach(channel -> {
            if (!CHANNEL_POWER_STATE.equals(channel.getUID().getId())) {
                updateState(channel.getUID(), UnDefType.NULL);
            }
        });
    }
}
