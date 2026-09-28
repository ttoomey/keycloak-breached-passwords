package org.greenriver.keycloak.breachedpasswords;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak with the provider loaded, plus WireMock serving the HIBP stubs on a shared network.
 * Started once per test JVM and shared by all test classes. Testcontainers removes the
 * containers when the JVM exits.
 */
final class TestEnvironment {

  static final String REALM = "breached-passwords-dev";

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
      new KeycloakContainer(
              Objects.requireNonNull(
                  System.getProperty("keycloak.image"),
                  "keycloak.image is unset; Surefire sets it from pom.xml, so run via mvn test"))
          .withNetwork(network)
          .withProviderClassesFrom("target/classes")
          .withEnv("KC_SPI_PASSWORD_POLICY__HIBP__BASE_URL", "http://hibp:8080")
          .withRealmImportFile("/realm/breached-passwords-dev-realm.json")
          .dependsOn(hibp);

  private TestEnvironment() {}

  static synchronized void start() {
    if (!keycloak.isRunning()) {
      keycloak.start();
    }
  }

  static String hibpUrl() {
    return "http://" + hibp.getHost() + ":" + hibp.getMappedPort(8080);
  }
}
