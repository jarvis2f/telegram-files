package telegram.files.maintains;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import telegram.files.DataVerticle;
import telegram.files.security.auth.AdminAuthService;

import java.io.Console;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class AdminMaintenanceCli extends MaintainVerticle {

    private final String[] args;

    private final Console console;

    private final PrintStream output;

    public AdminMaintenanceCli(String[] args, Console console, PrintStream output) {
        this.args = args == null ? new String[0] : Arrays.copyOf(args, args.length);
        this.console = console;
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        super.start(startPromise, this::runCommand);
    }

    @Override
    protected boolean initializeTelegram() {
        return false;
    }

    private void runCommand() {
        run(
                new AdminAuthService(vertx, DataVerticle.pool),
                args,
                console,
                output
        ).onComplete(result -> end(result.succeeded(), result.cause()));
    }

    public static Future<Void> run(
            AdminAuthService authService,
            String[] args,
            Console console,
            PrintStream output
    ) {
        Objects.requireNonNull(authService);
        Objects.requireNonNull(output);
        if (args.length != 3) {
            return Future.failedFuture(
                    "Usage: admin reset-password <username> | admin apply-reset <username>"
            );
        }
        String operation = args[1];
        String username = args[2];
        if ("reset-password".equals(operation)) {
            return authService.issuePasswordRecovery(username)
                    .onSuccess(recovery -> {
                        output.println(
                                "One-time local password recovery code for "
                                + recovery.username() + ": " + recovery.oneTimeToken()
                        );
                        output.println(
                                "Expires at: " + Instant.ofEpochMilli(recovery.expiresAt())
                        );
                        output.println(
                                "Run the maintenance command `admin apply-reset "
                                + recovery.username() + "` locally to set the new password."
                        );
                    })
                    .mapEmpty();
        }
        if (!"apply-reset".equals(operation)) {
            return Future.failedFuture("Unknown admin maintenance command");
        }
        if (console == null) {
            return Future.failedFuture(
                    "An interactive local console is required to apply password recovery"
            );
        }
        String recoveryToken = console.readLine("Recovery code: ");
        char[] first = console.readPassword("New password: ");
        char[] second = console.readPassword("Repeat new password: ");
        if (first == null || second == null || !Arrays.equals(first, second)) {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
            return Future.failedFuture("Passwords do not match");
        }
        return authService.applyPasswordRecovery(username, recoveryToken, first)
                .onSuccess(_ -> output.println("Administrator password reset successfully."))
                .eventually(() -> {
                    Arrays.fill(first, '\0');
                    Arrays.fill(second, '\0');
                    return Future.succeededFuture();
                });
    }
}
