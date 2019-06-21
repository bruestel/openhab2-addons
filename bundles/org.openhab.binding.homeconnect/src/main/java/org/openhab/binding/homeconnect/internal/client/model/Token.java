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
package org.openhab.binding.homeconnect.internal.client.model;

import java.util.Date;

/**
 * Token model
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class Token {

    private String accessToken;
    private String refreshToken;
    private long accessTokenExpiration;

    public Token() {
    }

    public Token(String accessToken, String refreshToken, long accessTokenExpiration) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiration = accessTokenExpiration;
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

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public void setAccessTokenExpiration(long accessTokenExpiration) {
        this.accessTokenExpiration = accessTokenExpiration;
    }

    @Override
    public String toString() {
        return "Token [accessToken=" + accessToken + ", refreshToken=" + refreshToken + ", accessTokenExpiration="
                + new Date(accessTokenExpiration) + "]";
    }

}
