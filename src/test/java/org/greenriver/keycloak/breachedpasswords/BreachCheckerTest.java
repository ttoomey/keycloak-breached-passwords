package org.greenriver.keycloak.breachedpasswords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.greenriver.keycloak.breachedpasswords.RangeClient.RangeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BreachCheckerTest {

  // SHA-1("password") = 5BAA6 + 1E4C9B93F3F0682250B6CF8331B7EE68FD8
  static final String PASSWORD = "password";
  static final String PREFIX = "5BAA6";
  static final String SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";
  static final String OTHER = "0018A45C4D1DEF81644B54AB7F969B88D65";

  final FakeRangeClient client = new FakeRangeClient();
  final List<Long> sleeps = new ArrayList<>();
  final BreachChecker checker = new BreachChecker(client, sleeps::add);

  @Test
  void splitsTheHashIntoPrefixAndSuffix() {
    BreachChecker.Hash hash = BreachChecker.Hash.of(PASSWORD);

    assertEquals(PREFIX, hash.prefix());
    assertEquals(SUFFIX, hash.suffix());
  }

  @Test
  void requestsThePrefix() {
    client.respond(200, OTHER + ":0");

    checker.isBreached(PASSWORD, "r");

    assertEquals(List.of(PREFIX), client.prefixes);
  }

  @Test
  void matchingSuffixWithPositiveCountIsBreached() {
    client.respond(200, OTHER + ":0\r\n" + SUFFIX + ":3\r\n");

    assertTrue(checker.isBreached(PASSWORD, "r"));
  }

  @Test
  void matchingSuffixWithZeroCountIsPadding() {
    client.respond(200, SUFFIX + ":0\r\n" + OTHER + ":5");

    assertFalse(checker.isBreached(PASSWORD, "r"));
  }

  @Test
  void matchesTheSuffixCaseInsensitively() {
    client.respond(200, SUFFIX.toLowerCase() + ":1");

    assertTrue(checker.isBreached(PASSWORD, "r"));
  }

  @Test
  void parsesLfOnlyBodies() {
    client.respond(200, OTHER + ":0\n" + SUFFIX + ":2\n");

    assertTrue(checker.isBreached(PASSWORD, "r"));
  }

  @Test
  void handlesCountsBeyondLongRange() {
    client.respond(200, SUFFIX + ":99999999999999999999999");

    assertTrue(checker.isBreached(PASSWORD, "r"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "\r\n\r\n",
        OTHER + ":0\r\nnot a line\r\n",
        OTHER + ":-1",
        OTHER + ":",
        "ABC:1",
        "<html>Not found</html>",
        // A bad line wins over a matching entry.
        SUFFIX + ":5\r\ngarbage",
      })
  void unparseableBodyFailsOpenWithoutRetry(String body) {
    client.respond(200, body);

    assertFalse(checker.isBreached(PASSWORD, "r"));
    assertEquals(1, client.prefixes.size());
    assertEquals(List.of(), sleeps);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 429})
  void retryableStatusIsAttemptedThreeTimesThenFailsOpen(int status) {
    client.respond(status, "").respond(status, "").respond(status, "");

    assertFalse(checker.isBreached(PASSWORD, "r"));
    assertEquals(3, client.prefixes.size());
  }

  @Test
  void ioExceptionIsAttemptedThreeTimesThenFailsOpen() {
    client
        .fail(new SocketTimeoutException())
        .fail(new IOException("reset"))
        .fail(new SocketTimeoutException());

    assertFalse(checker.isBreached(PASSWORD, "r"));
    assertEquals(3, client.prefixes.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 400, 403})
  void otherClientErrorFailsOpenAfterOneAttempt(int status) {
    client.respond(status, "nope");

    assertFalse(checker.isBreached(PASSWORD, "r"));
    assertEquals(1, client.prefixes.size());
  }

  @Test
  void breachedOnSecondAttemptRejectsWithoutAThirdCall() {
    client.respond(503, "").respond(200, SUFFIX + ":7");

    assertTrue(checker.isBreached(PASSWORD, "r"));
    assertEquals(2, client.prefixes.size());
  }

  @Test
  void backsOffBetweenAttempts() {
    client.respond(503, "").respond(503, "").respond(503, "");

    checker.isBreached(PASSWORD, "r");

    assertEquals(List.of(250L, 500L), sleeps);
  }

  @Test
  void interruptedBackoffFailsOpenAndKeepsTheInterruptFlag() {
    client.respond(503, "").respond(200, SUFFIX + ":7");
    BreachChecker interrupted =
        new BreachChecker(
            client,
            millis -> {
              throw new InterruptedException();
            });

    try {
      assertFalse(interrupted.isBreached(PASSWORD, "r"));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, client.prefixes.size());
    } finally {
      Thread.interrupted();
    }
  }

  static final class FakeRangeClient implements RangeClient {

    final List<String> prefixes = new ArrayList<>();
    private final Deque<Object> outcomes = new ArrayDeque<>();

    FakeRangeClient respond(int status, String body) {
      outcomes.add(new RangeResponse(status, body));
      return this;
    }

    FakeRangeClient fail(IOException e) {
      outcomes.add(e);
      return this;
    }

    @Override
    public RangeResponse fetch(String prefix) throws IOException {
      prefixes.add(prefix);
      Object outcome = outcomes.remove();
      if (outcome instanceof IOException e) {
        throw e;
      }
      return (RangeResponse) outcome;
    }
  }
}
