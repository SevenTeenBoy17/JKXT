package com.jkxt.security;

import java.io.Console;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Standalone interactive maintenance entry; deliberately not part of Spring startup. */
public final class InitialOperatorCli {
  interface Prompt {
    String line(String label);
    char[] secret(String label);
    void message(String text);
  }
  record Database(String host, int port, String name, String username, char[] password, String sslMode) {}
  @FunctionalInterface
  interface Action { UUID provision(Database database, InitialOperatorProvisioner.Request request) throws Exception; }
  private static final String ENABLE = "--provision-initial-operator";

  static int run(String[] args, Prompt prompt, Action action) {
    if (args.length != 1 || !ENABLE.equals(args[0]) || prompt == null) return 2;
    char[] databasePassword = null;
    char[] password = null;
    char[] confirmation = null;
    try {
      prompt.message("Initial formal operator maintenance. No defaults; all seeded demo accounts must already be disabled.");
      String host = required(prompt.line("Database host (no URL or credentials): "));
      int port = Integer.parseInt(required(prompt.line("Database port: ")));
      String name = required(prompt.line("Database name: "));
      String dbUser = required(prompt.line("Database user: "));
      String sslMode = required(prompt.line("TLS mode (verify-full; disable allowed only on loopback): "));
      if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\\[::1\\]") || port < 1 || port > 65535
          || !name.matches("[A-Za-z0-9_]{1,63}") || !dbUser.matches("[A-Za-z0-9_]{1,63}")
          || !("verify-full".equals(sslMode) || ("disable".equals(sslMode)
              && List.of("127.0.0.1", "localhost", "[::1]").contains(host)))) {
        throw new IllegalArgumentException("Invalid database connection input");
      }
      databasePassword = prompt.secret("Database password (hidden): ");
      if (databasePassword == null || databasePassword.length == 0) throw new IllegalArgumentException("Password required");
      String orgText = required(prompt.line("Exact organization UUID (private: configured fixed-org-id): "));
      if (!orgText.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
        throw new IllegalArgumentException("Canonical UUID required");
      }
      UUID org = UUID.fromString(orgText);
      String slug = required(prompt.line("Exact organization slug: "));
      String username = required(prompt.line("New username (3-60 ASCII letters/digits/dot/underscore/hyphen): "));
      String displayName = required(prompt.line("Display name: "));
      password = prompt.secret("Temporary operator password (hidden, 12+ characters, letter and digit, at most 72 UTF-8 bytes): ");
      confirmation = prompt.secret("Confirm temporary operator password (hidden): ");
      if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
        throw new IllegalArgumentException("Password confirmation mismatch");
      }
      var request = new InitialOperatorProvisioner.Request(org, slug, username, displayName, password);
      InitialOperatorProvisioner.validate(request);
      if (!"CREATE INITIAL OPERATOR".equals(prompt.line("Type CREATE INITIAL OPERATOR to confirm this one-time operation: "))) {
        throw new IllegalArgumentException("Not confirmed");
      }
      UUID userId = action.provision(new Database(host, port, name, dbUser, databasePassword, sslMode), request);
      prompt.message("Initial operator created: " + userId + ". Sign in over HTTPS and change the temporary password immediately.");
      return 0;
    } catch (IllegalArgumentException failure) {
      prompt.message("Refused: invalid input, password policy, or confirmation. No operator created.");
      return 2;
    } catch (IllegalStateException failure) {
      prompt.message("Refused: verify V31 migration, exact active organization, disabled demos, and absence of any prior formal operator or duplicate username.");
      return 2;
    } catch (Exception failure) {
      // JDBC exceptions can contain sensitive connection data; never print their messages or stack traces.
      prompt.message("Operation failed. No success was confirmed. Check database availability and inspect the operator/audit state before retrying.");
      return 1;
    } finally {
      if (databasePassword != null) Arrays.fill(databasePassword, '\0');
      if (password != null) Arrays.fill(password, '\0');
      if (confirmation != null) Arrays.fill(confirmation, '\0');
    }
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Input required");
    return value.trim();
  }

  public static void main(String[] args) {
    if (args.length != 1 || !ENABLE.equals(args[0])) {
      System.err.println("Disabled. To explicitly provision the first operator, use --provision-initial-operator in a local interactive terminal. No other arguments are accepted.");
      System.exit(2);
      return;
    }
    Console console = System.console();
    if (console == null) {
      System.err.println("Refused: a real interactive Console is required; redirected input and environment credentials are not supported.");
      System.exit(2);
      return;
    }
    Prompt prompt = new Prompt() {
      public String line(String label) { return console.readLine("%s", label); }
      public char[] secret(String label) { return console.readPassword("%s", label); }
      public void message(String text) { console.printf("%s%n", text); }
    };
    System.exit(run(args, prompt, (database, request) -> {
      Properties properties = new Properties();
      properties.setProperty("user", database.username());
      properties.setProperty("password", new String(database.password()));
      properties.setProperty("sslmode", database.sslMode());
      properties.setProperty("connectTimeout", "10");
      properties.setProperty("socketTimeout", "30");
      properties.setProperty("ApplicationName", "JKXT-initial-operator-cli");
      String url = "jdbc:postgresql://" + database.host() + ":" + database.port() + "/" + database.name();
      try {
        return new InitialOperatorProvisioner(() -> DriverManager.getConnection(url, properties)).provision(request);
      } finally {
        properties.remove("password");
      }
    }));
  }
}
