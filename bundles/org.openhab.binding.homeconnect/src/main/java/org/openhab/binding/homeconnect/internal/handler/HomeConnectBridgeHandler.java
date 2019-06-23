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

import static org.apache.commons.lang.StringUtils.isEmpty;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.OAuthHelper;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Token;
import org.openhab.binding.homeconnect.internal.configuration.ApiBridgeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomeConnectBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectBridgeHandler extends BaseBridgeHandler {

    private static final int REINITIALIZATION_LONG_DELAY = 120;
    private static final int REINITIALIZATION_MEDIUM_DELAY = 30;
    private static final int REINITIALIZATION_SHORT_DELAY = 5;

    private final Logger logger = LoggerFactory.getLogger(HomeConnectBridgeHandler.class);

    private @Nullable HomeConnectApiClient apiClient;
    private @Nullable ScheduledFuture<?> reinitializationFuture;

    public HomeConnectBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // not used for bridge
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);

        HomeConnectApiClient apiClient = getApiClient();

        if (logger.isDebugEnabled()) {
            if (apiClient != null) {
                logger.debug("Updating Home Connect bridge handler");
            } else {
                logger.debug("Initializing Home Connect bridge handler");
            }
        }

        if (apiClient != null) {
            // remove old api client
            apiClient.dispose();
        }

        // check for oAuth token
        ApiBridgeConfiguration config = getConfiguration();
        if (StringUtils.isEmpty(config.getRefreshToken())) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Please authenticate your account at http(s)://<YOUROPENHAB>:<YOURPORT>/homeconnect (e.g. http://192.168.178.100:8080/homeconnect).");
        } else {
            // check token
            String refreshToken = config.getRefreshToken();
            String accessToken = config.getAccessToken();
            try {
                if (isEmpty(accessToken)) {
                    Token token = OAuthHelper.refreshToken(config.getClientId(), config.getClientSecret(), refreshToken,
                            config.isSimulator());
                    updateToken(token.getAccessToken(), token.getRefreshToken());
                    accessToken = token.getAccessToken();
                    refreshToken = token.getRefreshToken();
                }

                // initialize api client
                apiClient = new HomeConnectApiClient(accessToken, config.isSimulator(), () -> {
                    try {
                        Token token = OAuthHelper.refreshToken(config.getClientId(), config.getClientSecret(),
                                config.getRefreshToken(), config.isSimulator());
                        updateToken(token.getAccessToken(), token.getRefreshToken());
                        return token.getAccessToken();
                    } catch (CommunicationException e) {
                        logger.error("Could not refresh access token! {}", e.getMessage());
                    }
                    return null;

                });
                setApiClient(apiClient);

                // check if client works
                apiClient.getHomeAppliances();
                updateStatus(ThingStatus.ONLINE);

                // update API clients of bridge children
                logger.debug("Bridge finished initializing process --> refresh client handlers");
                List<Thing> children = getThing().getThings();
                for (Thing thing : children) {
                    ThingHandler childHandler = thing.getHandler();
                    HomeConnectApiClient client = apiClient;
                    if (childHandler instanceof HomeConnectApiClientListener && client != null) {
                        ((HomeConnectApiClientListener) childHandler).refreshApiClient(client);
                    }
                }
            } catch (RuntimeException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Home Connect service is not reachable or a problem occurred! Retrying in a couple of seconds ("
                                + e.getMessage() + ").");
                scheduleReinitialize(REINITIALIZATION_LONG_DELAY);
            } catch (CommunicationException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Home Connect service is not reachable or a problem occurred! Retrying in a couple of seconds ("
                                + e.getMessage() + ").");
                scheduleReinitialize(REINITIALIZATION_MEDIUM_DELAY);
            }
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        HomeConnectApiClient client = apiClient;
        if (childHandler instanceof HomeConnectApiClientListener && client != null) {
            ((HomeConnectApiClientListener) childHandler).refreshApiClient(client);
        }
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> reinitializationFuture = this.reinitializationFuture;
        if (reinitializationFuture != null && !reinitializationFuture.isDone()) {
            reinitializationFuture.cancel(true);
        }
    }

    public @Nullable HomeConnectApiClient getApiClient() {
        return apiClient;
    }

    private void setApiClient(HomeConnectApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public ApiBridgeConfiguration getConfiguration() {
        return getConfigAs(ApiBridgeConfiguration.class);
    }

    public void updateToken(String accessToken, String refreshtoken) {
        Configuration configuration = editConfiguration();
        if (logger.isDebugEnabled()) {
            logger.debug("Token were updated. \naccess token (old): " + configuration.get("accessToken")
                    + "\naccess token (new): " + accessToken + "\nrefresh token (old): "
                    + configuration.get("refreshToken") + "\nrefresh token (new): " + refreshtoken);
        }
        configuration.put("refreshToken", refreshtoken);
        configuration.put("accessToken", accessToken);
        updateConfiguration(configuration);
    }

    public void reInitialize() {
        initialize();
    }

    private synchronized void scheduleReinitialize(int seconds) {
        ScheduledFuture<?> reinitializationFuture = this.reinitializationFuture;
        if (reinitializationFuture != null && !reinitializationFuture.isDone()) {
            logger.debug("Reinitialization is already scheduled. Starting in {} seconds.",
                    reinitializationFuture.getDelay(TimeUnit.SECONDS));
        } else {
            reinitializationFuture = scheduler.schedule(() -> {

                scheduler.schedule(() -> initialize(), REINITIALIZATION_SHORT_DELAY, TimeUnit.SECONDS);

            }, seconds, TimeUnit.SECONDS);
        }
    }

}
