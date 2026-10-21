package com.jkxt.security;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InitialOperatorCliTest {
  private static final String FLAG = "--provision-initial-operator";
  private static final UUID ORG = UUID.fromString("9ea21d5a-08af-491a-98ed-350f340a22e5");
  private static class Input implements InitialOperatorCli.Prompt {
    final Deque<String> lines = new ArrayDeque<>(List.of("127.0.0.1", "25441", "jkxt_test_cli", "jkxt_test", "disable",
        ORG.toString(), "test-school", "initial.operator", "Initial Operator", "CREATE INITIAL OPERATOR"));
    final char[] dbPassword = "SyntheticDbSecret".toCharArray();
    final char[] password = "SyntheticOperator@2026".toCharArray();
    final char[] confirmation = password.clone();
    final Deque<char[]> secrets = new ArrayDeque<>(List.of(dbPassword, password, confirmation));
    final List<String> output = new ArrayList<>();
    int reads;
    public String line(String label) { reads++; return lines.removeFirst(); }
    public char[] secret(String label) { reads++; return secrets.removeFirst(); }
    public void message(String text) { output.add(text); }
    void assertCleared() {
      assertThat(dbPassword).containsOnly('\0');
      assertThat(password).containsOnly('\0');
      assertThat(confirmation).containsOnly('\0');
    }
  }
  @Test void missingEnableFlagOrAdditionalArgumentsNeverReadOrConnect() {
    for (String[] args : List.of(new String[0], new String[]{FLAG, "plaintext-password"}, new String[]{"unknown"})) {
      Input input = new Input();
      assertThat(InitialOperatorCli.run(args, input, (db, request) -> { throw new AssertionError("connection forbidden"); })).isEqualTo(2);
      assertThat(input.reads).isZero();
    }
  }
  @Test void missingRealTerminalCannotUseStdinFallback() {
    assertThat(InitialOperatorCli.run(new String[]{FLAG}, null, (db, request) -> { throw new AssertionError("connection forbidden"); })).isEqualTo(2);
  }
  @Test void explicitlyConfirmedInteractiveInputProvisionsOnceAndClearsSecrets() {
    Input input = new Input();
    AtomicInteger calls = new AtomicInteger();
    assertThat(InitialOperatorCli.run(new String[]{FLAG}, input, (db, request) -> {
      calls.incrementAndGet();
      assertThat(db.host()).isEqualTo("127.0.0.1");
      assertThat(db.port()).isEqualTo(25441);
      assertThat(db.password()).isEqualTo("SyntheticDbSecret".toCharArray());
      assertThat(request.organizationId()).isEqualTo(ORG);
      assertThat(request.password()).isEqualTo("SyntheticOperator@2026".toCharArray());
      return UUID.randomUUID();
    })).isZero();
    assertThat(calls).hasValue(1);
    input.assertCleared();
    assertThat(input.output.toString()).doesNotContain("SyntheticDbSecret", "SyntheticOperator@2026");
  }
  @Test void passwordMismatchOrExplicitCancellationNeverConnectsAndClearsSecrets() {
    for (boolean mismatch : List.of(true, false)) {
      Input input = new Input();
      if (mismatch) input.confirmation[0] = 'X';
      else { input.lines.removeLast(); input.lines.addLast("NO"); }
      assertThat(InitialOperatorCli.run(new String[]{FLAG}, input, (db, request) -> { throw new AssertionError("connection forbidden"); })).isEqualTo(2);
      input.assertCleared();
    }
  }
  @Test void exceptionsCannotLeakPasswordsOrConnectionDetails() {
    Input input = new Input();
    assertThat(InitialOperatorCli.run(new String[]{FLAG}, input, (db, request) -> {
      throw new SQLException("SyntheticDbSecret SyntheticOperator@2026 jdbc:secret");
    })).isEqualTo(1);
    input.assertCleared();
    assertThat(input.output.toString()).doesNotContain("SyntheticDbSecret", "SyntheticOperator@2026", "jdbc:secret");
  }
  @Test void refusesJdbcParameterInjectionAndNonTlsRemoteConnections() {
    for (String host : List.of("localhost?password=secret", "user:secret@localhost", "remote.example.com")) {
      Input input = new Input();
      input.lines.removeFirst(); input.lines.addFirst(host);
      assertThat(InitialOperatorCli.run(new String[]{FLAG}, input, (db, request) -> { throw new AssertionError("connection forbidden"); })).isEqualTo(2);
    }
  }
}
