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
package org.openhab.binding.homeconnect.internal.client;

import static io.github.bucket4j.Bandwidth.classic;
import static io.github.bucket4j.Refill.intervally;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.HTTP_PROXY_ENABLED;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.HTTP_PROXY_HOST;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.HTTP_PROXY_PORT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.auth.client.oauth2.AccessTokenResponse;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthException;
import org.eclipse.smarthome.core.auth.client.oauth2.OAuthResponseException;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.ProxySetupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.research.ws.wadl.HTTPMethods;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;

/**
 * okHttp helper.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
@NonNullByDefault
public class OkHttpHelper {
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final int OAUTH_EXPIRE_BUFFER = 10;
    private static final JsonParser JSON_PARSER = new JsonParser();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(OkHttpHelper.class);
    private static final Bucket BUCKET = Bucket4j.builder()
            // allows 50 tokens per minute (added 10 second buffer)
            .addLimit(classic(50, intervally(50, Duration.ofSeconds(70))).withInitialTokens(40))
            // but not often then 50 tokens per second
            .addLimit(classic(10, intervally(10, Duration.ofSeconds(1))).withInitialTokens(0)).build();

    public static Builder builder(boolean enableRateLimiting) {
        Builder builder;
        if (HTTP_PROXY_ENABLED) {
            LOGGER.warn("Using http proxy! {}:{}", HTTP_PROXY_HOST, HTTP_PROXY_PORT);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(HTTP_PROXY_HOST, HTTP_PROXY_PORT));

            try {
                TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate @Nullable [] chain,
                            @Nullable String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate @Nullable [] chain,
                            @Nullable String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[] {};
                    }
                } };

                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                builder = new OkHttpClient().newBuilder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier((hostname, session) -> true).proxy(proxy);
            } catch (Exception e) {
                throw new ProxySetupException(e);
            }
        } else {
            builder = new OkHttpClient().newBuilder();
        }

        if (enableRateLimiting) {
            builder.addInterceptor(chain -> {
                if (HTTPMethods.GET.value().equals(chain.request().method())) {
                    try {
                        BUCKET.asScheduler().consume(1);
                    } catch (InterruptedException e) {
                        LOGGER.error("Rate limiting error! error={}", e.getMessage());
                    }
                }
                return chain.proceed(chain.request());
            });
        }
        return builder;
    }

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

    public static Request.Builder requestBuilder(OAuthClientService oAuthClientService)
            throws AuthorizationException, CommunicationException {
        try {
            @Nullable
            AccessTokenResponse accessTokenResponse = oAuthClientService.getAccessTokenResponse();

            // refresh the token if it's about to expire
            if (accessTokenResponse != null
                    && accessTokenResponse.isExpired(LocalDateTime.now(), OAUTH_EXPIRE_BUFFER)) {
                accessTokenResponse = oAuthClientService.refreshToken();
            }

            if (accessTokenResponse != null) {
                return new Request.Builder().addHeader(HEADER_AUTHORIZATION,
                        BEARER + accessTokenResponse.getAccessToken());
            } else {
                LOGGER.error("No access token available! Fatal error.");
                throw new AuthorizationException("No access token available!");
            }
        } catch (IOException e) {
            throw new CommunicationException(e.getMessage(), e);
        } catch (OAuthException | OAuthResponseException e) {
            throw new AuthorizationException(e.getMessage(), e);
        }
    }
}
