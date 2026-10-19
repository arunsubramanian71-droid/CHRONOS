package chronos.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * The CHRONOS command line (C16). It holds no logic of its own: every command calls the components below it (engine, store,
 * replay, time travel, branching, counterfactuals, comparator). See {@link Cli} for the commands.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.in));
    }

    /** Runs one command line; returns the process exit code (0 ok, 1 usage or user error, 2 verification failed, 3 internal error). */
    public static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        try {
            return new Cli(out, err, in).execute(Arrays.asList(args));
        } catch (CliException | IllegalArgumentException | IllegalStateException e) {
            err.println("chronos: " + e.getMessage());
            return 1;
        } catch (chronos.model.CorruptLogException | chronos.model.TimelineHeader.UnsupportedFormatException e) {
            err.println("chronos: the stored log is not usable: " + e.getMessage());
            return 2;
        } catch (chronos.model.UnknownTimelineException e) {
            err.println("chronos: " + e.getMessage() + " (see: chronos ls)");
            return 1;
        } catch (chronos.model.DeterminismViolation e) {
            err.println(e.report());
            return 2;
        } catch (RuntimeException e) {
            err.println("chronos: internal error: " + e);
            e.printStackTrace(err);
            return 3;
        }
    }
}
