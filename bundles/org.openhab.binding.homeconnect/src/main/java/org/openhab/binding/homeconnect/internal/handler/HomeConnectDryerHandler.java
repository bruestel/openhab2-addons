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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.StateDescription;
import org.eclipse.smarthome.core.types.StateDescriptionFragmentBuilder;
import org.eclipse.smarthome.core.types.StateOption;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgramOption;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jersey.repackaged.com.google.common.collect.ImmutableList;

/**
 * The {@link HomeConnectDryerHandler} is responsible for handling commands, which are
 * sent to one of the channels of a dryer.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectDryerHandler extends AbstractHomeConnectThingHandler {

    private static final ImmutableList<String> ACTIVE_STATE = ImmutableList.of(OPERATION_STATE_DELAYED_START,
            OPERATION_STATE_RUN, OPERATION_STATE_PAUSE);
    private static final ImmutableList<String> INACTIVE_STATE = ImmutableList.of(OPERATION_STATE_INACTIVE,
            OPERATION_STATE_READY);

    private final Logger logger = LoggerFactory.getLogger(HomeConnectDryerHandler.class);
    private HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    public HomeConnectDryerHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(thing, dynamicStateDescriptionProvider);
        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());
        handlers.put(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE, defaultLocalControlActiveStateChannelUpdateHandler());

        // register dryer specific handlers
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
                        case OPTION_DRYER_DRYING_TARGET:
                            getThingChannel(CHANNEL_DRYER_DRYING_TARGET).ifPresent(
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
                        case OPTION_DRYER_DRYING_TARGET:
                            getThingChannel(CHANNEL_DRYER_DRYING_TARGET).ifPresent(
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
        handlers.put(EVENT_LOCAL_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE));

        // register dryer specific event handlers
        handlers.put(EVENT_SELECTED_PROGRAM, event -> {
            defaultSelectedProgramStateEventHandler().handle(event);

            // update available program options
            updateProgramOptions(event.getValue());
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
        handlers.put(EVENT_DRYER_DRYING_TARGET, event -> {
            getThingChannel(CHANNEL_DRYER_DRYING_TARGET).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
    }

    @Override
    public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);
            String operationState = getOperationState();

            if (logger.isDebugEnabled()) {
                logger.debug("{}: {}", channelUID, command);
            }

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

                // set selected program
                if (command instanceof StringType && CHANNEL_SELECTED_PROGRAM_STATE.equals(channelUID.getId())) {
                    getApiClient().setSelectedProgram(getThingHaId(), command.toFullString());
                }

                // only handle these commands if operation state allows it
                if (operationState != null
                        && (ACTIVE_STATE.contains(operationState) || INACTIVE_STATE.contains(operationState))) {
                    boolean activeState = ACTIVE_STATE.contains(operationState);

                    if (logger.isDebugEnabled()) {
                        logger.debug("operation state: {} | active: {}", operationState, activeState);
                    }

                    // set drying target option
                    if (command instanceof StringType && CHANNEL_DRYER_DRYING_TARGET.equals(channelUID.getId())) {
                        getApiClient().setProgramOptions(getThingHaId(), OPTION_DRYER_DRYING_TARGET,
                                command.toFullString(), null, false, activeState);
                    }
                }

            } catch (CommunicationException e) {
                logger.warn("Could not handle command {}. API communication problem! error: {}", command.toFullString(),
                        e.getMessage());
            } catch (AuthorizationException e) {
                logger.warn("Could not handle command {}. Authorization problem! error: {}", command.toFullString(),
                        e.getMessage());

                handleAuthenticationError(e);
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectDryerHandler [haId: " + getThingHaId() + "]";
    }

    private void resetProgramStateChannels() {
        logger.debug("Resetting active program channel states");
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }

    private void updateProgramOptions(String programKey) {
        getThingChannel(CHANNEL_DRYER_DRYING_TARGET).map(channel -> channel.getUID().getAsString())
                .ifPresent(channelUID -> {
                    try {
                        List<AvailableProgramOption> availableProgramOptions = getApiClient()
                                .getProgramOptions(getThingHaId(), programKey);

                        if (availableProgramOptions.isEmpty()) {
                            dynamicStateDescriptionProvider.removeStateDescriptions(channelUID);
                        }

                        availableProgramOptions.forEach(option -> {
                            if (option.getKey() != null && OPTION_DRYER_DRYING_TARGET.equals(option.getKey())) {
                                ArrayList<StateOption> stateOptions = new ArrayList<>();

                                option.getAllowedValues()
                                        .forEach(av -> stateOptions.add(new StateOption(av, mapStringType(av))));
                                StateDescription stateDescription = StateDescriptionFragmentBuilder.create()
                                        .withPattern("%s").withReadOnly(stateOptions.isEmpty())
                                        .withOptions(stateOptions).build().toStateDescription();

                                if (stateDescription != null) {
                                    dynamicStateDescriptionProvider.putStateDescriptions(channelUID, stateDescription);
                                }
                            }
                        });
                    } catch (CommunicationException e) {
                        logger.error("Could not fetch available program options. {}", e.getMessage());
                    } catch (AuthorizationException e) {
                        logger.warn("Could not fetch available program options. {}", e.getMessage());

                        handleAuthenticationError(e);
                    }
                });
    }
}
