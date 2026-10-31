package fixtures;

/** Regression fixture: decompilers may emit an initializer javac rejects. Never execute. */
public class ThrowingInitializer {
    static { if (true) throw new IllegalStateException("fixture must never execute"); }
    public int value() { return 42; }
}
