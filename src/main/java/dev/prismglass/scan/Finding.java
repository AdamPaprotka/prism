package dev.prismglass.scan;

import java.util.*;

/** Everything learned about one plugin (or piece of server software): its evidence and the confidence they add up to. */
public final class Finding {
    /** Signature id, or "x:name" for something the database does not know. */
    public final String key;
    public final Signature sig;
    private String name;
    private final List<Evidence> evidence = new ArrayList<>();
    private final Set<String> commands = new TreeSet<>();

    Finding(String key, Signature sig, String name) {
        this.key = key;
        this.sig = sig;
        this.name = name;
    }

    public String name() { return name; }
    public String kind() { return sig == null ? "plugin" : sig.kind(); }
    public boolean builtin() { return sig != null && sig.builtin(); }
    public List<Evidence> evidence() { return Collections.unmodifiableList(evidence); }
    /** Commands seen for it (namespaced copies, matched names). */
    public Set<String> commands() { return commands; }

    /** A name the server printed itself has the right capitalisation: prefer it. */
    void rename(String better) { if (better != null && !better.isBlank()) name = better; }

    void add(Method m, String detail, int weight) {
        for (Evidence e : evidence) if (e.method() == m && e.detail().equals(detail)) return;
        evidence.add(new Evidence(m, detail, Math.max(0, Math.min(100, weight))));
    }

    /** Noisy-or over the strongest evidence of each family (0-100). */
    public int score() {
        Map<String, Integer> best = new HashMap<>();
        for (Evidence e : evidence) best.merge(e.method().family, e.weight(), Math::max);
        double miss = 1;
        for (int w : best.values()) miss *= 1 - w / 100.0;
        return (int) Math.round((1 - miss) * 100);
    }

    public Confidence confidence() { return Confidence.of(score()); }

    /** Methods that contributed, strongest first. */
    public List<Method> methods() {
        List<Evidence> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparingInt(Evidence::weight).reversed());
        LinkedHashSet<Method> out = new LinkedHashSet<>();
        for (Evidence e : sorted) out.add(e.method());
        return new ArrayList<>(out);
    }

    public boolean has(Method m) {
        for (Evidence e : evidence) if (e.method() == m) return true;
        return false;
    }
}
