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
package org.openhab.binding.homeconnect.internal.configuration;

/**
 * The {@link ApiBridgeConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Jonas Brüstel - Initial contribution
 */
public class ApiBridgeConfiguration {

    private String clientId;
    private String clientSecret;
    private String accessToken;
    private String refreshToken;
    private boolean simulator;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public boolean isSimulator() {
        return simulator;
    }

    public void setSimulator(boolean simulator) {
        this.simulator = simulator;
    }

    @Override
    public String toString() {
        return "ApiBridgeConfiguration [clientId=" + clientId + ", clientSecret=" + clientSecret + ", accessToken="
                + accessToken + ", refreshToken=" + refreshToken + ", simulator=" + simulator + "]";
    }

}
