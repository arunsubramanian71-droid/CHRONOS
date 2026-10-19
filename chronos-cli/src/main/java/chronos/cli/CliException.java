package chronos.cli;

/** A user error: bad arguments, unknown timeline, refused intervention. Reported with exit code 1 and no stack trace. */
final class CliException extends RuntimeException {
    CliException(String message) {
        super(message);
    }
}
