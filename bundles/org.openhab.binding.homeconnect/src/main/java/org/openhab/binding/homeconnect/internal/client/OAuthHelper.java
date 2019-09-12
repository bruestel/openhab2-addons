package org.openhab.binding.homeconnect.internal.client;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OAuthHelper {

    private final static Logger LOG = LoggerFactory.getLogger(OAuthHelper.class);

    public static String getAuthorizationUrl(String clientId, String state, boolean simulator) {
        StringBuilder sb = new StringBuilder();

        try {
            sb.append(simulator ? API_SIMULATOR_BASE_URL : API_BASE_URL);
            sb.append(OAUTH_AUTHORIZE_PATH);
            sb.append("?client_id=");
            sb.append(clientId);
            sb.append("&response_type=code");
            sb.append("&scope=");
            sb.append(OAUTH_SCOPE.replaceAll(" ", "%20"));
            sb.append("&state=");
            sb.append(URLEncoder.encode(state, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            LOG.error("[oAuth] Could not encode query parameter!", e);
        }

        return sb.toString();

    }

    public static Token getAccessAndRefreshTokenByAuthorizationCode(String clientId, String clientSecret,
            String oAuthCode, boolean simulator) throws CommunicationException {

        OkHttpClient client = OkHttpHelper.builder().followRedirects(false).followSslRedirects(false).build();

        RequestBody formBody = new FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code")
                .add("code", oAuthCode).add("client_secret", clientSecret).build();
        Request accessTokenRequest = new Request.Builder()
                .url((simulator ? API_SIMULATOR_BASE_URL : API_BASE_URL) + OAUTH_TOKEN_PATH).post(formBody).build();

        try (Response accessTokenResponse = client.newCall(accessTokenRequest).execute()) {
            if (accessTokenResponse.code() != HTTP_OK) {
                LOG.error("[oAuth] Couldn't get token!");
                int code = accessTokenResponse.code();
                String message = accessTokenResponse.message();
                String body = accessTokenResponse.body().string();
                throw new CommunicationException(code, message, body);
            }

            String accessTokenResponseBody = accessTokenResponse.body().string();
            JsonObject responseObject = new JsonParser().parse(accessTokenResponseBody).getAsJsonObject();
            String accessToken = responseObject.get("access_token").getAsString();
            String refreshToken = responseObject.get("refresh_token").getAsString();
            long expireDate = responseObject.get("expires_in").getAsLong() * 1000;
            LOG.debug(
                    "[oAuth] Access Token Request (Authorization Code Grant Flow).  access_token: {} refresh_token: {}",
                    accessToken, refreshToken);

            return new Token(accessToken, refreshToken, expireDate + System.currentTimeMillis());

        } catch (IOException e) {
            LOG.error("Error accured while communicating with API!");
            throw new CommunicationException(e);
        }
    }

    public static Token refreshToken(String clientId, String clientSecret, String refreshToken, boolean simulator)
            throws CommunicationException {
        LOG.debug("[oAuth] Refreshing access token. client_id: {}, refresh_token: {}", clientId, refreshToken);

        OkHttpClient client = OkHttpHelper.builder().followRedirects(false).followSslRedirects(false).build();

        RequestBody formBody = new FormBody.Builder().add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token").add("client_secret", clientSecret).build();

        Request request = new Request.Builder()
                .url((simulator ? API_SIMULATOR_BASE_URL : API_BASE_URL) + OAUTH_TOKEN_PATH).post(formBody).build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            int responseCode = response.code();
            String responseMessage = response.message();
            if (response.code() != HTTP_OK) {
                throw new CommunicationException(responseCode, responseMessage, responseBody);
            }
            LOG.debug("[oAuth] refresh token response code: {}, body: {}.", responseCode, responseBody);

            JsonObject responseObject = new JsonParser().parse(responseBody).getAsJsonObject();
            String accessToken = responseObject.get("access_token").getAsString();
            String newRefreshToken = responseObject.get("refresh_token").getAsString();
            long expireDate = responseObject.get("expires_in").getAsLong() * 1000;
            LOG.debug("[oAuth] Refresh Token Request.  access_token: {} refresh_token: {}", accessToken,
                    newRefreshToken);

            return new Token(accessToken, newRefreshToken, expireDate + System.currentTimeMillis());

        } catch (IOException e) {
            LOG.error("Error accured while communicating with API!");
            throw new CommunicationException(e);
        }
    }

}
