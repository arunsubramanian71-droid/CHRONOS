package chronos.model;

/** A timeline id that is not in the catalogue (Section 23, errors). */
public class UnknownTimelineException extends RuntimeException {
    public UnknownTimelineException(String id) {
        super("unknown timeline: " + id);
    }
}
