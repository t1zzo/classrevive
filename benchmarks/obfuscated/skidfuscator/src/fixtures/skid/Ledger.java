package fixtures.skid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Authored for ClassRevive's Skidfuscator corpus: instance methods called from inside the jar, loops and exceptions. */
public class Ledger {
    private final List<Entry> entries = new ArrayList<>();
    private final String owner;
    private int version;

    public Ledger(String owner) {
        if (owner == null || owner.isEmpty()) {
            throw new IllegalArgumentException("owner required");
        }
        this.owner = owner;
    }

    public void add(String label, Kind kind, long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("negative amount: " + cents);
        }
        entries.add(new Entry(label, kind, cents));
        version++;
    }

    public long total(Kind kind) {
        long sum = 0;
        for (Entry entry : entries) {
            if (kind == null || entry.kind() == kind) {
                sum += entry.cents();
            }
        }
        return sum;
    }

    public int count() {
        return entries.size();
    }

    public List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            labels.add(i + ":" + entries.get(i).label());
        }
        Collections.sort(labels);
        return labels;
    }

    public String owner() {
        return owner;
    }

    public int version() {
        return version;
    }

    public long parseAndAdd(String label, String amount) {
        try {
            long cents = Long.parseLong(amount.trim());
            add(label, Kind.OTHER, cents);
            return cents;
        } catch (NumberFormatException e) {
            return -1;
        } finally {
            version += 10;
        }
    }

    public static final class Entry {
        private final String label;
        private final Kind kind;
        private final long cents;

        Entry(String label, Kind kind, long cents) {
            this.label = label;
            this.kind = kind;
            this.cents = cents;
        }

        public String label() { return label; }
        public Kind kind() { return kind; }
        public long cents() { return cents; }

        @Override
        public String toString() {
            return label + "=" + cents + " (" + kind + ")";
        }
    }
}
