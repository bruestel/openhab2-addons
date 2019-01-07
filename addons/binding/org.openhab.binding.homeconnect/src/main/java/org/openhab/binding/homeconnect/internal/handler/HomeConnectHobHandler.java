/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.homeconnect.internal.handler;

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

import java.util.Optional;

import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.PERCENT;
import static org.eclipse.smarthome.core.library.unit.SmartHomeUnits.SECOND;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

/**
 * The {@link HomeConnectHobHandler} is responsible for handling commands, which are
 * sent to one of the channels of a hob.
 *
 * @author Nigel Magnay - Initial contribution
 */
@NonNullByDefault
public class HomeConnectHobHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectHobHandler.class);

    public HomeConnectHobHandler(Thing thing) {
        super(thing);

        // register default SSE event handlers

        registerEventHandler(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());
        registerEventHandler(EVENT_REMOTE_CONTROL_ACTIVE,
                defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        registerEventHandler(EVENT_LOCAL_CONTROL_ACTIVE,
                defaultBooleanEventHandler(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE));


        registerEventHandler(EVENT_SELECTED_PROGRAM, defaultSelectedProgramStateEventHandler());

        registerEventHandler(EVENT_PROGRAM_FINISHED, event -> {

        });

        registerEventHandler(EVENT_ALARM_ELAPSED, event -> {

        });

        registerEventHandler(EVENT_PREHEAT_FINISHED, event -> {

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


        // register default update handlers

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
       
    }

    @Override
    public String toString() {
        return "HomeConnectHobHandler [haId: " + getThingHaId() + "]";
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
