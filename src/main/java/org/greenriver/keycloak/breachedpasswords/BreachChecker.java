package org.greenriver.keycloak.breachedpasswords;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Checks a password against the HIBP range API, retrying transient failures and failing open
 * when HIBP can't answer.
 */
final class BreachChecker {

  static final int MAX_ATTEMPTS = 3;

  /** Delay before attempt 2, then before attempt 3. */
  static final long[] BACKOFF_MILLIS = {250, 500};

  private static final Logger LOG = Logger.getLogger(BreachChecker.class);

  private static final Pattern LINE = Pattern.compile("([0-9A-Fa-f]{35}):([0-9]+)");

  private final RangeClient client;
  private final Sleeper sleeper;

  BreachChecker(RangeClient client, Sleeper sleeper) {
    this.client = client;
    this.sleeper = sleeper;
  }

  /**
   * Returns true only if HIBP says the password is breached. Returns false when it isn't, or
   * when HIBP can't be reached or gives an unusable answer.
   */
  boolean isBreached(String password, String realmName) {
    Hash hash = Hash.of(password);
    String lastError = null;
    int attempt = 0;
    while (attempt < MAX_ATTEMPTS) {
      if (attempt > 0) {
        try {
          sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          lastError = "interrupted";
          break;
        }
      }
      attempt++;

      RangeClient.RangeResponse response;
      try {
        response = client.fetch(hash.prefix());
      } catch (IOException e) {
        lastError = e.getClass().getSimpleName();
        LOG.debugf("HIBP attempt %d failed: %s", attempt, lastError);
        continue;
      }

      int status = response.status();
      if (status == 200) {
        Boolean breached = parse(response.body(), hash.suffix());
        if (breached != null) {
          return breached;
        }
        lastError = "unparseable";
        LOG.debugf("HIBP attempt %d failed: %s", attempt, lastError);
        break;
      }
      lastError = String.valueOf(status);
      LOG.debugf("HIBP attempt %d failed: %s", attempt, lastError);
      if (status != 429 && status < 500) {
        break;
      }
    }

    LOG.warnf(
        "HIBP check unavailable after %d attempts (realm=%s, last error=%s); password accepted",
        attempt, realmName, lastError);
    return false;
  }

  /**
   * Returns whether {@code suffix} appears in {@code body} with a count above zero, or null if
   * the body is unparseable.
   */
  static Boolean parse(String body, String suffix) {
    if (body == null) {
      return null;
    }
    boolean sawEntry = false;
    boolean breached = false;
    for (String line : body.split("\n", -1)) {
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (line.isBlank()) {
        continue;
      }
      Matcher m = LINE.matcher(line);
      if (!m.matches()) {
        return null;
      }
      sawEntry = true;
      // Padding entries have count 0. Checking digits avoids overflow on huge counts.
      if (m.group(1).equalsIgnoreCase(suffix) && !m.group(2).chars().allMatch(c -> c == '0')) {
        breached = true;
      }
    }
    return sawEntry ? breached : null;
  }

  /** Uppercase hex SHA-1 of the UTF-8 password, split into the 5-char prefix and the rest. */
  record Hash(String prefix, String suffix) {

    static Hash of(String password) {
      MessageDigest sha1;
      try {
        sha1 = MessageDigest.getInstance("SHA-1");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-1 is unavailable", e);
      }
      String hex =
          HexFormat.of()
              .withUpperCase()
              .formatHex(sha1.digest(password.getBytes(StandardCharsets.UTF_8)));
      return new Hash(hex.substring(0, 5), hex.substring(5));
    }

    @Override
    public String toString() {
      // Keep the hash out of logs and exception messages.
      return "Hash[redacted]";
    }
  }
}
