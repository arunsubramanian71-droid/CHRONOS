package chronos.sdk;

import java.util.List;
import java.util.Map;

/**
 * The only door from a node to the outside world (Section 9): send, set and cancel timers, read virtual time, draw random
 * numbers, read configuration, annotate the log. Nothing here touches a socket, a clock or a thread.
 */
public interface NodeContext {
    /** This node's name. */
    String self();

    /** Names of all nodes of the scenario in declared order. */
    List<String> nodes();

    /** Virtual time in milliseconds. A clock-skew fault (Section 18) would change only this value, never the logical clocks. */
    long now();

    /** The node's state; handlers read and write it here, and only here. */
    NodeState state();

    /** Sends a message; returns its id (the id of the send event). The runtime picks the delivery time. */
    String send(String to, String type, Map<String, Object> body);

    /** Sets a timer to fire after delayMs of virtual time; returns its id (the id of the TIMER_SET event). */
    String setTimer(String name, long delayMs);

    /** Cancels a pending timer; returns false if it was not pending (already fired or cancelled). */
    boolean cancelTimer(String timerId);

    /** Draws the next value of one of this node's own seeded random streams (recorded as a RANDOM_DRAWN event). */
    long random(String stream);

    /** A bounded draw in [0, bound). */
    default long random(String stream, long bound) {
        return Math.floorMod(random(stream), bound);
    }

    /** Scenario configuration value, or null. */
    String config(String key);

    /** Free-text label in the log, for readability only; it has no causal effect. */
    void note(String text);
}
