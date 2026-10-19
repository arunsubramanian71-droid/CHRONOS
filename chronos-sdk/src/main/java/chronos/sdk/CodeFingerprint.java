package chronos.sdk;

import chronos.model.Canon;

import java.io.IOException;
import java.io.InputStream;

/**
 * Best-effort identification of application code (Q7, Section 15): a hash of the compiled classes of a scenario and its
 * nodes. A mismatch on replay produces a warning, not a refusal, because developers legitimately replay an old failing
 * log against fixed code (that is itself a comparison).
 */
public final class CodeFingerprint {
    private CodeFingerprint() {}

    public static String of(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        append(sb, scenario.getClass());
        for (NodeSpec n : scenario.nodes()) append(sb, n.factory().get().getClass());
        return Canon.sha256(sb.toString()).substring(0, 16);
    }

    private static void append(StringBuilder sb, Class<?> c) {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(res)) {
            if (in == null) {
                sb.append(c.getName()).append(';');
                return;
            }
            sb.append(c.getName()).append('=').append(Canon.sha256(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1))).append(';');
        } catch (IOException e) {
            sb.append(c.getName()).append(";");
        }
    }
}
