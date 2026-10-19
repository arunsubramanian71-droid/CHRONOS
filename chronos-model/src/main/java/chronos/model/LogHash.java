package chronos.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** The hash of an event log (G2, AD-2): SHA-256 over the canonical line of every event, in position order. */
public final class LogHash {
    private final MessageDigest md;

    public LogHash() {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void add(Event e) {
        md.update(e.toLine().getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
    }

    public String hex() {
        try {
            byte[] d = ((MessageDigest) md.clone()).digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(List<Event> events) {
        LogHash h = new LogHash();
        for (Event e : events) h.add(e);
        return h.hex();
    }
}
