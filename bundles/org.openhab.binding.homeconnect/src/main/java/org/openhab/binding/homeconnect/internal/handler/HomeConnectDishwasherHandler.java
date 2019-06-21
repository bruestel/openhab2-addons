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
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.ConfigurationException;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectDishwasherHandler} is responsible for handling commands, which are
 * sent to one of the channels of a dishwasher.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectDishwasherHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectDishwasherHandler.class);

    public HomeConnectDishwasherHandler(Thing thing) {
        super(thing);

        // register default SSE event handlers
        registerEventHandler(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        registerEventHandler(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());
        registerEventHandler(EVENT_REMOTE_CONTROL_ACTIVE,
                defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        registerEventHandler(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        registerEventHandler(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        registerEventHandler(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        registerEventHandler(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());

        // register dishwasher specific SSE event handlers
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
        registerEventHandler(EVENT_POWER_STATE, event -> {
            getThingChannel(CHANNEL_POWER_STATE).ifPresent(channel -> updateState(channel.getUID(),
                    STATE_POWER_ON.equals(event.getValue()) ? OnOffType.ON : OnOffType.OFF));

            if (!STATE_POWER_ON.equals(event.getValue())) {
                resetProgramStateChannels();
                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
                getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
            }

        });

        // register default update handlers
        registerChannelUpdateHandler(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_POWER_STATE, defaultPowerStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE,
                defaultRemoteControlActiveStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE,
                defaultRemoteStartAllowanceChannelUpdateHandler());

        // register dishwasher specific update handlers
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
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
                resetProgramStateChannels();
            }
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        super.handleCommand(channelUID, command);

        if (command instanceof OnOffType && CHANNEL_POWER_STATE.equals(channelUID.getId())) {
            try {
                // turn dishwasher on and off
                getClient().setPowerState(getThingHaId(),
                        OnOffType.ON.equals(command) ? STATE_POWER_ON : STATE_POWER_OFF);
            } catch (ConfigurationException | CommunicationException e) {
                logger.error("API communication problem!", e);
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectDishwasherHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }
}
