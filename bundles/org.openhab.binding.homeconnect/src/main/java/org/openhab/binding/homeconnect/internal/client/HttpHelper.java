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
package org.openhab.binding.homeconnect.internal.client;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthResponseException;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * okHttp helper.
 *
 * @author Jonas Brüstel - Initial contribution
 * @author Laurent Garnier - Removed okhttp
 *
 */
@NonNullByDefault
public class HttpHelper {
    private static final String BEARER = "Bearer ";
    private static final int OAUTH_EXPIRE_BUFFER = 10;
    private static final JsonParser JSON_PARSER = new JsonParser();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static @Nullable String lastAccessToken = null;

    public static String formatJsonBody(@Nullable String jsonString) {
        if (jsonString == null) {
            return "";
        }
        try {
            JsonObject json = JSON_PARSER.parse(jsonString).getAsJsonObject();
            return GSON.toJson(json);
        } catch (Exception e) {
            return jsonString;
        }
    }

    public static String getAuthorizationHeader(OAuthClientService oAuthClientService)
            throws AuthorizationException, CommunicationException {
        try {
            @Nullable
            AccessTokenResponse accessTokenResponse = oAuthClientService.getAccessTokenResponse();

            // refresh the token if it's about to expire
            if (accessTokenResponse != null
                    && accessTokenResponse.isExpired(LocalDateTime.now(), OAUTH_EXPIRE_BUFFER)) {
                LoggerFactory.getLogger(HttpHelper.class).debug("Requesting a refresh of the access token.");
                accessTokenResponse = oAuthClientService.refreshToken();
            }

            if (accessTokenResponse != null) {
                String lastToken = lastAccessToken;
                if (lastToken == null) {
                    LoggerFactory.getLogger(HttpHelper.class).debug("The used access token was created at {}",
                            accessTokenResponse.getCreatedOn().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else if (!lastToken.equals(accessTokenResponse.getAccessToken())) {
                    LoggerFactory.getLogger(HttpHelper.class).debug("The access token changed. New one created at {}",
                            accessTokenResponse.getCreatedOn().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }
                lastAccessToken = accessTokenResponse.getAccessToken();
                return BEARER + accessTokenResponse.getAccessToken();
            } else {
                LoggerFactory.getLogger(HttpHelper.class).error("No access token available! Fatal error.");
                throw new AuthorizationException("No access token available!");
            }
        } catch (IOException e) {
            String errorMessage = e.getMessage();
            throw new CommunicationException(errorMessage != null ? errorMessage : "IOException", e);
        } catch (OAuthException | OAuthResponseException e) {
            String errorMessage = e.getMessage();
            throw new AuthorizationException(errorMessage != null ? errorMessage : "oAuth exception", e);
        }
    }
}
