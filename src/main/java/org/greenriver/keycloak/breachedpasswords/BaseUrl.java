package org.greenriver.keycloak.breachedpasswords;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates and normalizes the {@code base-url} SPI option. */
final class BaseUrl {

  private BaseUrl() {}

  /**
   * Returns {@code value} without trailing slashes.
   *
   * @throws IllegalArgumentException unless {@code value} is an absolute http(s) URI with a host
   */
  static String parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("base-url must not be blank");
    }
    String trimmed = value.strip();
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("base-url is not a valid URI: " + trimmed, e);
    }
    String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("base-url must be an absolute http or https URI: " + trimmed);
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new IllegalArgumentException("base-url must have a host: " + trimmed);
    }
    // "/range/{prefix}" is appended, so a query or fragment would swallow the path.
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "base-url must not have a query or fragment: " + trimmed);
    }
    int end = trimmed.length();
    while (end > 0 && trimmed.charAt(end - 1) == '/') {
      end--;
    }
    return trimmed.substring(0, end);
  }
}
