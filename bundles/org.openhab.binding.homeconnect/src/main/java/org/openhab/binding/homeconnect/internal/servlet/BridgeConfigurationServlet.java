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
package org.openhab.binding.homeconnect.internal.servlet;

import static org.apache.commons.lang.StringUtils.isEmpty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Thing;
import org.openhab.binding.homeconnect.internal.client.OAuthHelper;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Token;
import org.openhab.binding.homeconnect.internal.configuration.ApiBridgeConfiguration;
import org.openhab.binding.homeconnect.internal.handler.HomeConnectBridgeHandler;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * HomeConnect bridge configuration servlet.
 *
 * @author Jonas Brüstel - Initial Contribution
 */
@NonNullByDefault
public class BridgeConfigurationServlet extends AbstractServlet {

    private final static long serialVersionUID = -9058701058178609079L;
    private final static String TEMPLATE = "bridge_configuration.html";
    private final static String TEMPLATE_BRIDGE = "part_bridge.html";
    private final static String TEMPLATE_SUCCESS = "success.html";
    private final static String PLACEHOLDER_KEY_BRIDGES = "bridges";
    private final static String PLACEHOLDER_KEY_LABEL = "label";
    private final static String PLACEHOLDER_KEY_UID = "uid";
    private final static String PLACEHOLDER_KEY_CLIENT_ID = "clientId";
    private final static String PLACEHOLDER_KEY_CLIENT_SECRET = "clientSecret";

    private final Logger logger = LoggerFactory.getLogger(BridgeConfigurationServlet.class);
    private final ArrayList<HomeConnectBridgeHandler> bridgeHandlers;

    public BridgeConfigurationServlet(HttpService httpService, BundleContext bundleContext,
            ArrayList<HomeConnectBridgeHandler> bridgeHandlers) {
        super(httpService, bundleContext);

        this.bridgeHandlers = bridgeHandlers;

        try {
            logger.debug("Initialize bridge configuration servlet... ({})", SERVLET_BASE_PATH);
            httpService.registerServlet(SERVLET_BASE_PATH, this, null, httpService.createDefaultHttpContext());
        } catch (NamespaceException e) {
            try {
                httpService.unregister(SERVLET_BASE_PATH);
                httpService.registerServlet(SERVLET_BASE_PATH, this, null, httpService.createDefaultHttpContext());
            } catch (ServletException | NamespaceException ex) {
                logger.error("Could not register bridge configuration servlet! ({})", SERVLET_BASE_PATH, ex);
            }
        } catch (ServletException e) {
            logger.error("Could not register bridge configuration servlet! ({})", SERVLET_BASE_PATH, e);
        }

    }

    @SuppressWarnings("null")
    @Override
    protected void doGet(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws ServletException, IOException {
        logger.debug("GET {}", SERVLET_BASE_PATH);

        String code = request.getParameter("code");
        String state = request.getParameter("state");

        if (!isEmpty(code) && !isEmpty(state)) {
            // callback handling from authorization server
            logger.debug("[oAuth] redirect from authorization server (code={}, state={}).", code, state);

            HomeConnectBridgeHandler bridgeHandler = getBridgeHandler(state);
            if (bridgeHandler == null) {
                response.sendError(HttpStatus.SC_BAD_REQUEST, "unknown bridge");
            } else {
                ApiBridgeConfiguration config = bridgeHandler.getConfiguration();
                try {
                    Token token = OAuthHelper.getAccessAndRefreshTokenByAuthorizationCode(config.getClientId(),
                            config.getClientSecret(), code, config.isSimulator());

                    // save token info and inform bridge handler
                    bridgeHandler.updateToken(token.getAccessToken(), token.getRefreshToken());
                    bridgeHandler.reInitialize();

                    final HashMap<String, String> replaceMap = new HashMap<>();
                    replaceMap.put(PLACEHOLDER_KEY_LABEL, bridgeHandler.getThing().getLabel() + "");
                    replaceMap.put(PLACEHOLDER_KEY_UID, bridgeHandler.getThing().getUID().getAsString());

                    response.setContentType(CONTENT_TYPE);
                    response.getWriter().append(replaceKeysFromMap(readHtmlTemplate(TEMPLATE_SUCCESS), replaceMap));
                    response.getWriter().close();
                } catch (CommunicationException e) {
                    logger.error("Could not fetch token!", e);
                    response.sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, "Could not fetch token!");
                }
            }

        } else {
            // index page
            final HashMap<String, String> replaceMap = new HashMap<>();
            if (bridgeHandlers.isEmpty()) {
                replaceMap.put(PLACEHOLDER_KEY_BRIDGES,
                        "<p class='block'>No HomeConnect bridge found. Please manually add 'Home Connect API' bridge and authorize it here.<p>");
            } else {
                replaceMap.put(PLACEHOLDER_KEY_BRIDGES, bridgeHandlers.stream()
                        .map(bridgeHandler -> renderBridgePart(bridgeHandler)).collect(Collectors.joining()));
            }

            response.setContentType(CONTENT_TYPE);
            response.getWriter().append(replaceKeysFromMap(readHtmlTemplate(TEMPLATE), replaceMap));
            response.getWriter().close();
        }
    }

    @SuppressWarnings("null")
    @Override
    protected void doPost(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws ServletException, IOException {

        String bridgeUid = request.getParameter("uid");

        if (StringUtils.isEmpty(bridgeUid)) {
            response.sendError(HttpStatus.SC_BAD_REQUEST, "uid parameter missing");
        } else {

            HomeConnectBridgeHandler bridgeHandler = getBridgeHandler(bridgeUid);
            if (bridgeHandler == null) {
                response.sendError(HttpStatus.SC_BAD_REQUEST, "unknown bridge");
            } else {
                ApiBridgeConfiguration config = bridgeHandler.getConfiguration();
                String authorizationUrl = OAuthHelper.getAuthorizationUrl(config.getClientId(),
                        bridgeHandler.getThing().getUID().getAsString(), config.isSimulator());
                logger.debug("Generated authorization url: {}", authorizationUrl);
                response.sendRedirect(authorizationUrl);
            }
        }
    }

    public void dispose() {
        httpService.unregister(SERVLET_BASE_PATH);
    }

    private @Nullable HomeConnectBridgeHandler getBridgeHandler(String bridgeUid) {
        for (HomeConnectBridgeHandler handler : bridgeHandlers) {
            if (handler.getThing().getUID().getAsString().equals(bridgeUid)) {
                return handler;
            }
        }
        return null;
    }

    private String renderBridgePart(HomeConnectBridgeHandler bridgeHandler) {
        Thing thing = bridgeHandler.getThing();
        HashMap<String, String> replaceMap = new HashMap<>();

        replaceMap.put(PLACEHOLDER_KEY_LABEL, thing.getLabel() + "");
        replaceMap.put(PLACEHOLDER_KEY_UID, thing.getUID().getAsString());

        ApiBridgeConfiguration configuration = bridgeHandler.getConfiguration();
        replaceMap.put(PLACEHOLDER_KEY_CLIENT_ID, configuration.getClientId());
        replaceMap.put(PLACEHOLDER_KEY_CLIENT_SECRET, configuration.getClientSecret());

        try {
            return replaceKeysFromMap(readHtmlTemplate(TEMPLATE_BRIDGE), replaceMap);
        } catch (IOException e) {
            logger.error("Could not render template {}!", TEMPLATE_BRIDGE, e);
            return "";
        }
    }
}
