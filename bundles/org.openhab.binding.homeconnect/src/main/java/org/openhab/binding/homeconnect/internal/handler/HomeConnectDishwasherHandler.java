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

import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.openhab.binding.homeconnect.internal.logger.EmbeddedLoggingService;
import org.openhab.binding.homeconnect.internal.logger.Logger;

/**
 * The {@link HomeConnectDishwasherHandler} is responsible for handling commands, which are
 * sent to one of the channels of a dishwasher.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectDishwasherHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger;

    public HomeConnectDishwasherHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            EmbeddedLoggingService loggingService) {
        super(thing, dynamicStateDescriptionProvider, loggingService);
        logger = loggingService.getLogger(HomeConnectDishwasherHandler.class);
        resetProgramStateChannels();
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        handlers.put(CHANNEL_POWER_STATE, defaultPowerStateChannelUpdateHandler());
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());
        handlers.put(CHANNEL_SELECTED_PROGRAM_STATE, defaultSelectedProgramStateUpdateHandler());

        // register dishwasher specific update handlers
        handlers.put(CHANNEL_ACTIVE_PROGRAM_STATE, (channelUID, client) -> {
            Program program = client.getActiveProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(mapStringType(program.getKey())));
                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_REMAINING_PROGRAM_TIME:

                            getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE)
                                    .ifPresent(channel -> updateState(channel.getUID(),
                                            new QuantityType<>(option.getValueAsInt(), SECOND)));
                            break;
                        case OPTION_PROGRAM_PROGRESS:
                            getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE)
                                    .ifPresent(channel -> updateState(channel.getUID(),
                                            new QuantityType<>(option.getValueAsInt(), PERCENT)));
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
    protected void configureEventHandlers(ConcurrentHashMap<String, EventHandler> handlers) {
        // register default SSE event handlers
        handlers.put(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        handlers.put(EVENT_REMOTE_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        handlers.put(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        handlers.put(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        handlers.put(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());

        // register dishwasher specific SSE event handlers
        handlers.put(EVENT_ACTIVE_PROGRAM, event -> {
            defaultActiveProgramEventHandler().handle(event);

            if (event.getValue() == null) {
                resetProgramStateChannels();
            }
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

        handlers.put(EVENT_POWER_STATE, event -> {
            defaultPowerStateEventHandler().handle(event);

            if (!STATE_POWER_ON.equals(event.getValue())) {
                resetProgramStateChannels();
                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
            }
            if (STATE_POWER_ON.equals(event.getValue())) {
                getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).ifPresent(c -> updateChannel(c.getUID()));
            }
        });

    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);

            try {
                // start or stop program
                if (command instanceof StringType && CHANNEL_BASIC_ACTIONS_STATE.equals(channelUID.getId())) {
                    updateState(channelUID, new StringType(""));

                    if ("start".equalsIgnoreCase(command.toFullString())) {
                        getApiClient().startSelectedProgram(getThingHaId());
                    } else {
                        getApiClient().stopProgram(getThingHaId());
                    }
                }

                // set selected program of dishwasher
                if (command instanceof StringType && CHANNEL_SELECTED_PROGRAM_STATE.equals(channelUID.getId())) {
                    getApiClient().setSelectedProgram(getThingHaId(), command.toFullString());
                }

                // turn dishwasher on and off
                if (command instanceof OnOffType && CHANNEL_POWER_STATE.equals(channelUID.getId())) {
                    getApiClient().setPowerState(getThingHaId(),
                            OnOffType.ON.equals(command) ? STATE_POWER_ON : STATE_POWER_OFF);
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
        return "HomeConnectDishwasherHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debugWithHaId(getThingHaId(), "Resetting active program channel states.");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }
}
