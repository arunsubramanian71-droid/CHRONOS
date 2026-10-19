package chronos.cli;

import java.util.*;

/** A tiny hand-written argument parser (no CLI framework, Section 23): positionals plus --option [value] pairs. */
final class Args {
    private static final Set<String> FLAGS = Set.of("no-stop", "strict", "chaos", "table", "diagram", "dot", "json", "world", "inflight",
            "timers", "keep", "help", "full", "quiet");

    final List<String> pos = new ArrayList<>();
    private final Map<String, List<String>> opt = new LinkedHashMap<>();

    static Args parse(List<String> a) {
        Args r = new Args();
        for (int i = 0; i < a.size(); i++) {
            String t = a.get(i);
            if (t.startsWith("--") && t.length() > 2) {
                String k = t.substring(2);
                int eq = k.indexOf('=');
                if (eq > 0) {
                    r.opt.computeIfAbsent(k.substring(0, eq), x -> new ArrayList<>()).add(k.substring(eq + 1));
                } else if (FLAGS.contains(k)) {
                    r.opt.computeIfAbsent(k, x -> new ArrayList<>()).add("true");
                } else if (i + 1 < a.size() && !a.get(i + 1).startsWith("--")) {
                    r.opt.computeIfAbsent(k, x -> new ArrayList<>()).add(a.get(++i));
                } else {
                    r.opt.computeIfAbsent(k, x -> new ArrayList<>()).add("");
                }
            } else {
                r.pos.add(t);
            }
        }
        return r;
    }

    boolean has(String k) {
        return opt.containsKey(k);
    }

    String get(String k) {
        List<String> l = opt.get(k);
        return l == null ? null : l.get(l.size() - 1);
    }

    String get(String k, String dflt) {
        String v = get(k);
        return v == null ? dflt : v;
    }

    List<String> all(String k) {
        return opt.getOrDefault(k, List.of());
    }

    long num(String k, long dflt) {
        String v = get(k);
        if (v == null || v.isEmpty()) return dflt;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new CliException("--" + k + " expects a number, got '" + v + "'");
        }
    }

    int positionalCount() {
        return pos.size();
    }

    String positional(int i, String what) {
        if (i >= pos.size()) throw new CliException("missing argument: " + what);
        return pos.get(i);
    }
}
