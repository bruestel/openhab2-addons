/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.homeconnect.internal.discovery;

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.BINDING_ID;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.DISCOVERABLE_DEVICE_THING_TYPES_UIDS;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.HA_ID;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_COFFEE_MAKER;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_COOKTOP;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_DISHWASHER;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_DRYER;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_FRIDGE_FREEZER;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_HOOD;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_OVEN;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_WASHER;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.THING_TYPE_WASHER_DRYER;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectBridgeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectDiscoveryService} is responsible for discovering new devices.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectDiscoveryService extends AbstractDiscoveryService {

    private static final int SEARCH_TIME = 20;

    private final HomeConnectBridgeHandler bridgeHandler;
    private final Logger logger;

    /**
     * Construct an {@link HomeConnectDiscoveryService} with the given
     * {@link org.eclipse.smarthome.core.thing.binding.BridgeHandler}.
     *
     * @param bridgeHandler bridge handler
     */
    public HomeConnectDiscoveryService(HomeConnectBridgeHandler bridgeHandler) {
        super(DISCOVERABLE_DEVICE_THING_TYPES_UIDS, SEARCH_TIME, true);
        this.bridgeHandler = bridgeHandler;
        logger = LoggerFactory.getLogger(HomeConnectDiscoveryService.class);
    }

    @Override
    protected void startScan() {
        logger.debug("Starting device scan.");

        HomeConnectApiClient apiClient = bridgeHandler.getApiClient();

        try {
            List<HomeAppliance> appliances = apiClient.getHomeAppliances();
            logger.debug("Scan found {} devices.", appliances.size());

            // add found devices
            for (HomeAppliance appliance : appliances) {
                @Nullable
                ThingTypeUID thingTypeUID = getThingTypeUID(appliance);

                if (alreadyExists(appliance.getHaId())) {
                    logger.debug("Device {} ({}) already added as thing.", appliance.getHaId(),
                            appliance.getType().toUpperCase());
                } else if (thingTypeUID != null && DISCOVERABLE_DEVICE_THING_TYPES_UIDS.contains(thingTypeUID)) {
                    logger.info("Found {} ({}).", appliance.getHaId(), appliance.getType().toUpperCase());
                    bridgeHandler.getThing().getThings().forEach(thing -> thing.getProperties().get(HA_ID));

                    Map<String, Object> properties = new HashMap<>();
                    properties.put(HA_ID, appliance.getHaId());
                    String name = appliance.getBrand() + " " + appliance.getName() + " (" + appliance.getHaId() + ")";

                    DiscoveryResult discoveryResult = DiscoveryResultBuilder
                            .create(new ThingUID(BINDING_ID, appliance.getType(),
                                    bridgeHandler.getThing().getUID().getId(), appliance.getHaId()))
                            .withThingType(thingTypeUID).withProperties(properties)
                            .withBridge(bridgeHandler.getThing().getUID()).withLabel(name).build();
                    thingDiscovered(discoveryResult);
                } else {
                    logger.debug("Ignoring unsupported device {} of type {}.", appliance.getHaId(),
                            appliance.getType());
                }
            }
        } catch (Exception e) {
            logger.error("Exception during scan.", e);
        }
        logger.debug("Finished device scan.");
    }

    @Override
    public void deactivate() {
        super.deactivate();
        removeOlderResults(new Date().getTime());
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan());
    }

    /**
     * Check if device is already connected to the bridge.
     *
     * @param haId home appliance id
     * @return true if appliance exists, otherwise false
     */
    private boolean alreadyExists(String haId) {
        boolean exists = false;
        List<Thing> children = bridgeHandler.getThing().getThings();
        for (Thing child : children) {
            if (haId.equals(child.getConfiguration().get(HA_ID))) {
                exists = true;
            }
        }
        return exists;
    }

    private @Nullable ThingTypeUID getThingTypeUID(HomeAppliance appliance) {
        @Nullable
        ThingTypeUID thingTypeUID = null;

        if (THING_TYPE_DISHWASHER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_DISHWASHER;
        } else if (THING_TYPE_OVEN.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_OVEN;
        } else if (THING_TYPE_FRIDGE_FREEZER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_FRIDGE_FREEZER;
        } else if (THING_TYPE_DRYER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_DRYER;
        } else if (THING_TYPE_COFFEE_MAKER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_COFFEE_MAKER;
        } else if (THING_TYPE_HOOD.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_HOOD;
        } else if (THING_TYPE_WASHER_DRYER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_WASHER_DRYER;
        } else if (THING_TYPE_COOKTOP.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_COOKTOP;
        } else if (THING_TYPE_WASHER.getId().equalsIgnoreCase(appliance.getType())) {
            thingTypeUID = THING_TYPE_WASHER;
        }

        return thingTypeUID;
    }
}
