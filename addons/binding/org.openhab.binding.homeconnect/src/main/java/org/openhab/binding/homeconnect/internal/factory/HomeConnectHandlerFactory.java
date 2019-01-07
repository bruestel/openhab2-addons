/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.homeconnect.internal.factory;

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.openhab.binding.homeconnect.internal.discovery.HomeConnectDiscoveryService;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectBridgeHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectCoffeeMakerHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectDishwasherHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectDryerHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectFridgeFreezerHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectHobHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectOvenHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectWasherHandler;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;

/**
 * The {@link HomeConnectHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.homeconnect", service = ThingHandlerFactory.class)
public class HomeConnectHandlerFactory extends BaseThingHandlerFactory {

    private final Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegistrations = new HashMap<>();

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_DEVICE_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_API_BRIDGE.equals(thingTypeUID)) {
            HomeConnectBridgeHandler bridgeHandler = new HomeConnectBridgeHandler((Bridge) thing);

            // configure discovery service
            HomeConnectDiscoveryService discoveryService = new HomeConnectDiscoveryService(bridgeHandler);
            discoveryServiceRegistrations.put(bridgeHandler.getThing().getUID(), bundleContext
                    .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<>()));

            return bridgeHandler;
        } else if (THING_TYPE_DISHWASHER.equals(thingTypeUID)) {
            return new HomeConnectDishwasherHandler(thing);
        } else if (THING_TYPE_OVEN.equals(thingTypeUID)) {
            return new HomeConnectOvenHandler(thing);
        } else if (THING_TYPE_WASHER.equals(thingTypeUID)) {
            return new HomeConnectWasherHandler(thing);
        } else if (THING_TYPE_DRYER.equals(thingTypeUID)) {
            return new HomeConnectDryerHandler(thing);
        } else if (THING_TYPE_FRIDGE_FREEZER.equals(thingTypeUID)) {
            return new HomeConnectFridgeFreezerHandler(thing);
        } else if (THING_TYPE_COFFEE_MAKER.equals(thingTypeUID)) {
            return new HomeConnectCoffeeMakerHandler(thing);
        }  else if (THING_TYPE_HOB.equals(thingTypeUID)) {
            return new HomeConnectHobHandler(thing);
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof HomeConnectBridgeHandler) {
            ServiceRegistration<?> serviceRegistration = discoveryServiceRegistrations
                    .get(thingHandler.getThing().getUID());
            HomeConnectDiscoveryService service = (HomeConnectDiscoveryService) bundleContext
                    .getService(serviceRegistration.getReference());
            service.deactivate();
            serviceRegistration.unregister();
            discoveryServiceRegistrations.remove(thingHandler.getThing().getUID());

        }
    }

}
