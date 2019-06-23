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
package org.openhab.binding.homeconnect.internal.factory;

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.ArrayList;
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
import org.openhab.binding.homeconnect.internal.handler.HomeConnectDynamicStateDescriptionProvider;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectFridgeFreezerHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectOvenHandler;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectWasherHandler;
import org.openhab.binding.homeconnect.internal.servlet.BridgeConfigurationServlet;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.homeconnect", service = ThingHandlerFactory.class)
public class HomeConnectHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(HomeConnectHandlerFactory.class);

    private final Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegistrations = new HashMap<>();

    private @NonNullByDefault({}) HttpService httpService;
    private @NonNullByDefault({}) BridgeConfigurationServlet bridgeConfigurationServlet;
    private @NonNullByDefault({}) HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    private final ArrayList<HomeConnectBridgeHandler> bridgeHandlers = new ArrayList<HomeConnectBridgeHandler>();

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_DEVICE_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected void activate(ComponentContext componentContext) {
        super.activate(componentContext);

        HttpService httpService = this.httpService;
        BridgeConfigurationServlet bridgeConfigurationServlet = this.bridgeConfigurationServlet;
        if (bridgeConfigurationServlet == null && httpService != null) {
            bridgeConfigurationServlet = new BridgeConfigurationServlet(httpService,
                    componentContext.getBundleContext(), bridgeHandlers);
        }
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        HttpService httpService = this.httpService;
        if (THING_TYPE_API_BRIDGE.equals(thingTypeUID)) {
            if (httpService != null) {
                HomeConnectBridgeHandler bridgeHandler = new HomeConnectBridgeHandler((Bridge) thing);
                bridgeHandlers.add(bridgeHandler);

                // configure discovery service
                HomeConnectDiscoveryService discoveryService = new HomeConnectDiscoveryService(bridgeHandler);
                discoveryServiceRegistrations.put(bridgeHandler.getThing().getUID(), bundleContext
                        .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<>()));

                return bridgeHandler;
            } else {
                logger.error("No HttpService available! Cannot initiate bridge handler.");
            }
        } else if (THING_TYPE_DISHWASHER.equals(thingTypeUID)) {
            return new HomeConnectDishwasherHandler(thing, dynamicStateDescriptionProvider);
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
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof HomeConnectBridgeHandler) {
            bridgeHandlers.remove(thingHandler);

            ServiceRegistration<?> serviceRegistration = discoveryServiceRegistrations
                    .get(thingHandler.getThing().getUID());
            HomeConnectDiscoveryService service = (HomeConnectDiscoveryService) bundleContext
                    .getService(serviceRegistration.getReference());
            service.deactivate();
            serviceRegistration.unregister();
            discoveryServiceRegistrations.remove(thingHandler.getThing().getUID());

        }
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        if (bridgeConfigurationServlet != null) {
            bridgeConfigurationServlet.dispose();
        }
        super.deactivate(componentContext);
    }

    @Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.DYNAMIC)
    protected void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }

    protected void unsetHttpService(HttpService httpService) {
        this.httpService = null;
    }

    @Reference
    protected void setDynamicStateDescriptionProvider(HomeConnectDynamicStateDescriptionProvider provider) {
        this.dynamicStateDescriptionProvider = provider;
    }

    protected void unsetDynamicStateDescriptionProvider(HomeConnectDynamicStateDescriptionProvider provider) {
        this.dynamicStateDescriptionProvider = null;
    }

}
