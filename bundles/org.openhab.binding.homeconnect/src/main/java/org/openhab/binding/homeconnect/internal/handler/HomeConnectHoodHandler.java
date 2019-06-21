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

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.StateDescription;
import org.eclipse.smarthome.core.types.StateDescriptionFragmentBuilder;
import org.eclipse.smarthome.core.types.StateOption;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgramOption;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectHoodHandler} is responsible for handling commands, which are
 * sent to one of the channels of a hood.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectHoodHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectHoodHandler.class);
    private HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    public HomeConnectHoodHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(thing, dynamicStateDescriptionProvider);
        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
        resetProgramStateChannels();
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_POWER_STATE, defaultPowerStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE, defaultLocalControlActiveStateChannelUpdateHandler());

        // register hood specific update handlers
        handlers.put(CHANNEL_ACTIVE_PROGRAM_STATE, (channelUID, client) -> {
            Program program = client.getActiveProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateState(channelUID, new StringType(mapStringType(program.getKey())));
                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_HOOD_INTENSIVE_LEVEL:
                            getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_HOOD_VENTING_LEVEL:
                            getThingChannel(CHANNEL_HOOD_VENTING_LEVEL).ifPresent(
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

                updateProgramOptions(program.getKey());

                program.getOptions().forEach(option -> {
                    switch (option.getKey()) {
                        case OPTION_HOOD_INTENSIVE_LEVEL:
                            getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                        case OPTION_HOOD_VENTING_LEVEL:
                            getThingChannel(CHANNEL_HOOD_VENTING_LEVEL).ifPresent(
                                    channel -> updateState(channel.getUID(), new StringType(option.getValue())));
                            break;
                    }
                });
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });
        handlers.put(CHANNEL_HOOD_INTENSIVE_LEVEL, (channelUID, client) -> {
            // only update channel if channel CHANNEL_SELECTED_PROGRAM_STATE is not there
            if (!getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).isPresent()) {
                Program program = client.getSelectedProgram(getThingHaId());
                if (program != null && program.getKey() != null) {
                    updateProgramOptions(program.getKey());
                }
            }
        });
        handlers.put(CHANNEL_HOOD_VENTING_LEVEL, (channelUID, client) -> {
            // only update channel if channel CHANNEL_SELECTED_PROGRAM_STATE and CHANNEL_HOOD_INTENSIVE_LEVEL are not
            // there
            if (!getThingChannel(CHANNEL_SELECTED_PROGRAM_STATE).isPresent()
                    && !getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL).isPresent()) {
                Program program = client.getSelectedProgram(getThingHaId());
                if (program != null && program.getKey() != null) {
                    updateProgramOptions(program.getKey());
                }
            }
        });
    }

    @Override
    protected void configureEventHandlers(ConcurrentHashMap<String, EventHandler> handlers) {
        // register default SSE event handlers
        handlers.put(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        handlers.put(EVENT_REMOTE_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_LOCAL_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());

        // register hood specific SSE event handlers
        handlers.put(EVENT_ACTIVE_PROGRAM, event -> {
            defaultActiveProgramEventHandler().handle(event);

            if (event.getValue() == null) {
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
        handlers.put(EVENT_HOOD_INTENSIVE_LEVEL, event -> {
            getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_HOOD_VENTING_LEVEL, event -> {
            getThingChannel(CHANNEL_HOOD_VENTING_LEVEL).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_SELECTED_PROGRAM, event -> {
            defaultSelectedProgramStateEventHandler().handle(event);

            // update available program options
            updateProgramOptions(event.getValue());
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

                // set selected program of hood
                if (command instanceof StringType && CHANNEL_SELECTED_PROGRAM_STATE.equals(channelUID.getId())) {
                    getClient().setSelectedProgram(getThingHaId(), command.toFullString());
                }

                // turn hood on and off
                if (command instanceof OnOffType && CHANNEL_POWER_STATE.equals(channelUID.getId())) {
                    getClient().setPowerState(getThingHaId(),
                            OnOffType.ON.equals(command) ? STATE_POWER_ON : STATE_POWER_OFF);
                }

                // program options
                String operationState = getCurrentOperationState();
                if (OPERATION_STATE_RUN.equals(operationState) || OPERATION_STATE_INACTIVE.equals(operationState)) {
                    boolean activeState = OPERATION_STATE_RUN.equals(operationState);
                    logger.debug("operation state: {} | active: {}", operationState, activeState);

                    // set intensive level
                    if (command instanceof StringType && CHANNEL_HOOD_INTENSIVE_LEVEL.equals(channelUID.getId())) {
                        getClient().setProgramOptions(getThingHaId(), OPTION_HOOD_INTENSIVE_LEVEL,
                                command.toFullString(), null, false, activeState);
                    } else if (command instanceof StringType && CHANNEL_HOOD_VENTING_LEVEL.equals(channelUID.getId())) {
                        getClient().setProgramOptions(getThingHaId(), OPTION_HOOD_VENTING_LEVEL, command.toFullString(),
                                null, false, activeState);
                    }
                }
            } catch (CommunicationException e) {
                logger.warn("Could not handle command {}. API communication problem! error: {}", command.toFullString(),
                        e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectHoodHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_HOOD_VENTING_LEVEL).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }

    private void updateProgramOptions(String programKey) {
        try {
            List<AvailableProgramOption> availableProgramOptions = getClient().getProgramOptions(getThingHaId(),
                    programKey);

            if (availableProgramOptions.isEmpty()) {
                dynamicStateDescriptionProvider.removeStateDescriptions(CHANNEL_HOOD_INTENSIVE_LEVEL);
                dynamicStateDescriptionProvider.removeStateDescriptions(CHANNEL_HOOD_VENTING_LEVEL);
            }

            availableProgramOptions.forEach(option -> {
                if (option.getKey() != null && OPTION_HOOD_INTENSIVE_LEVEL.equals(option.getKey())) {
                    ArrayList<StateOption> stateOptions = new ArrayList<>();

                    option.getAllowedValues().forEach(av -> stateOptions.add(new StateOption(av, convertLevel(av))));
                    StateDescription stateDescription = StateDescriptionFragmentBuilder.create().withPattern("%s")
                            .withReadOnly(stateOptions.isEmpty()).withOptions(stateOptions).build()
                            .toStateDescription();

                    if (stateDescription != null) {
                        Optional<Channel> channel = getThingChannel(CHANNEL_HOOD_INTENSIVE_LEVEL);
                        if (channel.isPresent()) {
                            dynamicStateDescriptionProvider.putStateDescriptions(channel.get().getUID().getAsString(),
                                    stateDescription);
                        }
                    }
                } else if (option.getKey() != null && OPTION_HOOD_VENTING_LEVEL.equals(option.getKey())) {
                    ArrayList<StateOption> stateOptions = new ArrayList<>();

                    option.getAllowedValues().forEach(av -> stateOptions.add(new StateOption(av, convertLevel(av))));
                    StateDescription stateDescription = StateDescriptionFragmentBuilder.create().withPattern("%s")
                            .withReadOnly(stateOptions.isEmpty()).withOptions(stateOptions).build()
                            .toStateDescription();

                    if (stateDescription != null) {
                        Optional<Channel> channel = getThingChannel(CHANNEL_HOOD_VENTING_LEVEL);
                        if (channel.isPresent()) {
                            dynamicStateDescriptionProvider.putStateDescriptions(channel.get().getUID().getAsString(),
                                    stateDescription);
                        }
                    }
                }
            });
        } catch (CommunicationException e) {
            logger.error("Could not fetch available program options. {}", e.getMessage());
        }
    }

    private String convertLevel(String value) {
        if (value.startsWith("Cooking.Hood.EnumType.IntensiveStage.IntensiveStage")) {
            return value.replace("Cooking.Hood.EnumType.IntensiveStage.IntensiveStage", "");
        }

        if (value.startsWith("Cooking.Hood.EnumType.Stage.FanStage0")) {
            return value.replace("Cooking.Hood.EnumType.Stage.FanStage0", "");
        }

        return mapStringType(value);
    }
}
