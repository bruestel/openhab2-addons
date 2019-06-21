package org.openhab.binding.homeconnect.internal.client.model;

import java.util.Date;

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
