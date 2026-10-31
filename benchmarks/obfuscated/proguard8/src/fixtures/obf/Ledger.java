package fixtures.obf;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/** Serializable on purpose: the ProGuard config omits the usual serialization keep rules. */
final class Ledger implements Serializable {
    private static final long serialVersionUID = 7L;
    private final String owner;
    private int balance;
    private transient int audits;

    Ledger(String owner) {
        this.owner = owner;
    }

    void record(int amount) {
        balance = clamp(balance + amount);
        audits++;
    }

    int total() {
        return owner.isEmpty() ? 0 : balance;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : value;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    static final class Entry {
        private final int amount;

        Entry(int amount) {
            this.amount = amount;
        }

        private int doubled() {
            return amount * 2;
        }

        int value() {
            return doubled();
        }
    }
}
