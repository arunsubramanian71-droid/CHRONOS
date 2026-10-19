package chronos.model;

/** A stored timeline is corrupt or inconsistent; the message cites the file, line or event id (Section 23, errors). */
public class CorruptLogException extends RuntimeException {
    public CorruptLogException(String message) {
        super(message);
    }
}
