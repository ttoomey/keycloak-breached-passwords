package org.greenriver.keycloak.breachedpasswords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the test tooling works: Keycloak boots with the dev realm, and
 * WireMock serves the HIBP stubs on a network Keycloak shares.
 */
class EnvironmentSmokeTest {

  static final Network network = Network.newNetwork();

  static final GenericContainer<?> hibp =
      new GenericContainer<>("wiremock/wiremock:3.13.2")
          .withNetwork(network)
          .withNetworkAliases("hibp")
          .withExposedPorts(8080)
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("wiremock/mappings"), "/home/wiremock/mappings")
          .waitingFor(Wait.forHttp("/__admin/health"));

  static final KeycloakContainer keycloak =
      new KeycloakContainer(System.getProperty("keycloak.image"))
          .withNetwork(network)
          .withRealmImportFile("/realm/dev-realm.json")
          .dependsOn(hibp);

  static final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    keycloak.start();
  }

  @AfterAll
  static void stop() {
    keycloak.stop();
    hibp.stop();
    network.close();
  }

  @Test
  void keycloakImportsTheDevRealm() throws Exception {
    HttpResponse<String> response = get(keycloak.getAuthServerUrl() + "/realms/breached-passwords-dev");

    assertEquals(200, response.statusCode());
  }

  @Test
  void wiremockServesThePwnedPasswordStub() throws Exception {
    // SHA-1("password") = 5BAA6 + 1E4C9B93F3F0682250B6CF8331B7EE68FD8
    HttpResponse<String> response =
        get("http://" + hibp.getHost() + ":" + hibp.getMappedPort(8080) + "/range/5BAA6");

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("1E4C9B93F3F0682250B6CF8331B7EE68FD8:10434004"));
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
