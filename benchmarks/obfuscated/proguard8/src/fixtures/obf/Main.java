package fixtures.obf;

/** Kept entry point: the only public API ProGuard is told to preserve. */
public final class Main {
    public static String describe(int amount) {
        Ledger ledger = new Ledger("ops");
        ledger.record(amount);
        Ledger.Entry entry = new Ledger.Entry(amount);
        Vault vault = new Vault();
        return vault.label() + ":" + ledger.total() + ":" + entry.value() + ":" + vault.trail();
    }
}
