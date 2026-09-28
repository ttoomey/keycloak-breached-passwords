package org.greenriver.keycloak.breachedpasswords;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakSession;

/** {@link RangeClient} on Keycloak's shared HTTP client, with per-request timeouts. */
final class KeycloakRangeClient implements RangeClient {

  static final String USER_AGENT = "keycloak-breached-passwords/1.0";

  static final int CONNECTION_REQUEST_TIMEOUT_MILLIS = 500;
  static final int CONNECT_TIMEOUT_MILLIS = 500;
  static final int SOCKET_TIMEOUT_MILLIS = 1000;

  private static final RequestConfig REQUEST_CONFIG =
      RequestConfig.custom()
          .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MILLIS)
          .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
          .setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
          .build();

  private final KeycloakSession session;
  private final String baseUrl;

  KeycloakRangeClient(KeycloakSession session, String baseUrl) {
    this.session = session;
    this.baseUrl = baseUrl;
  }

  @Override
  public RangeResponse fetch(String prefix) throws IOException {
    HttpGet request = new HttpGet(baseUrl + "/range/" + prefix);
    request.setConfig(REQUEST_CONFIG);
    request.setHeader("Add-Padding", "true");
    request.setHeader(HttpHeaders.USER_AGENT, USER_AGENT);

    // The client is Keycloak's shared instance: close the response to free the connection,
    // never the client.
    try (CloseableHttpResponse response =
        session.getProvider(HttpClientProvider.class).getHttpClient().execute(request)) {
      HttpEntity entity = response.getEntity();
      String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
      return new RangeResponse(response.getStatusLine().getStatusCode(), body);
    }
  }
}
