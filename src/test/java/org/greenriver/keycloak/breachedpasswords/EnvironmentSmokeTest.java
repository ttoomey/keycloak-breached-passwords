package org.greenriver.keycloak.breachedpasswords;

import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.REALM;
import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.hibpUrl;
import static org.greenriver.keycloak.breachedpasswords.TestEnvironment.keycloak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Proves the test tooling works: Keycloak boots with the provider and the dev
 * realm, and WireMock serves the HIBP stubs on a network Keycloak shares.
 */
class EnvironmentSmokeTest {

  static final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    TestEnvironment.start();
  }

  @Test
  void keycloakImportsTheDevRealm() throws Exception {
    HttpResponse<String> response = get(keycloak.getAuthServerUrl() + "/realms/" + REALM);

    assertEquals(200, response.statusCode());
  }

  @Test
  void wiremockServesThePwnedPasswordStub() throws Exception {
    // SHA-1("password") = 5BAA6 + 1E4C9B93F3F0682250B6CF8331B7EE68FD8
    HttpResponse<String> response = get(hibpUrl() + "/range/5BAA6");

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("1E4C9B93F3F0682250B6CF8331B7EE68FD8:10434004"));
  }

  @Test
  void keycloakReachesWiremockByNetworkAlias() throws Exception {
    // The Keycloak image has no curl, so send a raw request over bash's /dev/tcp.
    ExecResult result =
        keycloak.execInContainer(
            "bash",
            "-c",
            "exec 3<>/dev/tcp/hibp/8080"
                + " && printf 'GET /range/5BAA6 HTTP/1.0\\r\\nHost: hibp\\r\\n\\r\\n' >&3"
                + " && cat <&3");

    assertEquals(0, result.getExitCode(), result.getStderr());
    assertTrue(result.getStdout().contains("1E4C9B93F3F0682250B6CF8331B7EE68FD8:10434004"));
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
