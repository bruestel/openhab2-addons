/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerService;
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
public class HomeConnectDiscoveryService extends AbstractDiscoveryService
        implements DiscoveryService, ThingHandlerService {

    private static final int SEARCH_TIME = 20;

    private final Logger logger = LoggerFactory.getLogger(HomeConnectDiscoveryService.class);

    private @NonNullByDefault({}) HomeConnectBridgeHandler bridgeHandler;

    /**
     * Construct an {@link HomeConnectDiscoveryService}.
     *
     */
    public HomeConnectDiscoveryService() {
        super(DISCOVERABLE_DEVICE_THING_TYPES_UIDS, SEARCH_TIME, true);
    }

    @Override
    public void setThingHandler(@NonNullByDefault({}) ThingHandler handler) {
        if (handler instanceof HomeConnectBridgeHandler) {
            this.bridgeHandler = (HomeConnectBridgeHandler) handler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
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

                if (thingTypeUID != null) {
                    logger.debug("Found {} ({}).", appliance.getHaId(), appliance.getType().toUpperCase());

                    Map<String, Object> properties = new HashMap<>();
                    properties.put(HA_ID, appliance.getHaId());
                    String name = appliance.getBrand() + " " + appliance.getName() + " (" + appliance.getHaId() + ")";

                    DiscoveryResult discoveryResult = DiscoveryResultBuilder
                            .create(new ThingUID(BINDING_ID, appliance.getType(),
                                    bridgeHandler.getThing().getUID().getId(), appliance.getHaId()))
                            .withThingType(thingTypeUID).withProperties(properties).withRepresentationProperty(HA_ID)
                            .withBridge(bridgeHandler.getThing().getUID()).withLabel(name).build();
                    thingDiscovered(discoveryResult);
                } else {
                    logger.debug("Ignoring unsupported device {} of type {}.", appliance.getHaId(),
                            appliance.getType());
                }
            }
        } catch (Exception e) {
            logger.debug("Exception during scan.", e);
        }
        logger.debug("Finished device scan.");
    }

    @Override
    public void deactivate() {
        super.deactivate();
        removeOlderResults(System.currentTimeMillis(), bridgeHandler.getThing().getUID());
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan(), bridgeHandler.getThing().getUID());
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
