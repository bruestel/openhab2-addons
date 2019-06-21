/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.cert.CertificateException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;

/**
 * okHttp helper.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class OkHttpHelper {

    private static final Logger LOG = LoggerFactory.getLogger(OkHttpHelper.class);

    public static Builder builder() {
        if (HTTP_PROXY_ENABLED) {
            LOG.warn("Using http proxy! {}:{}", HTTP_PROXY_HOST, HTTP_PROXY_PORT);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(HTTP_PROXY_HOST, HTTP_PROXY_PORT));

            try {
                TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                            throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                            throws CertificateException {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[] {};
                    }
                } };

                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                return new OkHttpClient().newBuilder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier(new HostnameVerifier() {

                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return true;
                            }
                        }).proxy(proxy);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        return new OkHttpClient().newBuilder();
    }
}
