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

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectFridgeFreezerHandler} is responsible for handling commands, which are
 * sent to one of the channels of a fridge/freezer.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectFridgeFreezerHandler extends AbstractHomeConnectThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectFridgeFreezerHandler.class);

    public HomeConnectFridgeFreezerHandler(Thing thing) {
        super(thing);

        // register SSE event handlers
        registerEventHandler(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        registerEventHandler(EVENT_FREEZER_SETPOINT_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_FREEZER_SETPOINT_TEMPERATURE).ifPresent(channel -> updateState(channel.getUID(),
                    new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });
        registerEventHandler(EVENT_FRIDGE_SETPOINT_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_REFRIDGERATOR_SETPOINT_TEMPERATURE)
                    .ifPresent(channel -> updateState(channel.getUID(),
                            new QuantityType<>(event.getValueAsInt(), mapTemperature(event.getUnit()))));
        });
        registerEventHandler(EVENT_FREEZER_SUPER_MODE, defaultBooleanEventHandler(CHANNEL_FREEZER_SUPER_MODE));
        registerEventHandler(EVENT_FRIDGE_SUPER_MODE, defaultBooleanEventHandler(CHANNEL_REFRIDGERATOR_SUPER_MODE));

        // register update handlers
        registerChannelUpdateHandler(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        registerChannelUpdateHandler(CHANNEL_FREEZER_SETPOINT_TEMPERATURE, (channelUID, client) -> {
            Data data = client.getFreezerSetpointTemperature(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, new QuantityType<>(data.getValueAsInt(), mapTemperature(data.getUnit())));
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });
        registerChannelUpdateHandler(CHANNEL_REFRIDGERATOR_SETPOINT_TEMPERATURE, (channelUID, client) -> {
            Data data = client.getFridgeSetpointTemperature(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, new QuantityType<>(data.getValueAsInt(), mapTemperature(data.getUnit())));
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });
        registerChannelUpdateHandler(CHANNEL_REFRIDGERATOR_SUPER_MODE, (channelUID, client) -> {
            Data data = client.getFridgeSuperMode(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, data.getValueAsBoolean() ? OnOffType.ON : OnOffType.OFF);
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });
        registerChannelUpdateHandler(CHANNEL_FREEZER_SUPER_MODE, (channelUID, client) -> {
            Data data = client.getFreezerSuperMode(getThingHaId());
            if (data != null && data.getValue() != null) {
                updateState(channelUID, data.getValueAsBoolean() ? OnOffType.ON : OnOffType.OFF);
            } else {
                updateState(channelUID, UnDefType.NULL);
            }
        });

    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        super.handleCommand(channelUID, command);

        try {
            if (command instanceof QuantityType) {
                @SuppressWarnings("unchecked")
                QuantityType<Temperature> quantity = ((QuantityType<Temperature>) command);

                String unit = quantity.getUnit().toString();
                String value = String.valueOf(quantity.intValue());

                if (CHANNEL_REFRIDGERATOR_SETPOINT_TEMPERATURE.equals(channelUID.getId())) {
                    getClient().setFridgeSetpointTemperature(getThingHaId(), value, unit);
                } else if (CHANNEL_FREEZER_SETPOINT_TEMPERATURE.equals(channelUID.getId())) {
                    getClient().setFreezerSetpointTemperature(getThingHaId(), value, unit);
                }

            }
        } catch (CommunicationException e) {
            logger.error("API communication problem while trying to update {}!", getThingHaId(), e);
        }
    }

    @Override
    public String toString() {
        return "HomeConnectFridgeFreezerHandler [haId: " + getThingHaId() + "]";
    }
}
