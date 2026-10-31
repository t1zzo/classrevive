package dev.classrevive.transforms;

import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Makes the real JVM verifier check pass output; linking via getDeclaredMethods runs no fixture code. */
final class JvmVerification {
    private JvmVerification() {}

    /** Verifies output that differs from input; refused (unchanged) inputs may be deliberately malformed. */
    static void assertVerifiesIfChanged(byte[] input, byte[] output) {
        if (Arrays.equals(input, output)) return;
        var type = new ClassLoader(null) { Class<?> define() { return defineClass(null, output, 0, output.length); } }.define();
        assertDoesNotThrow(type::getDeclaredMethods, "transformed class must pass JVM verification");
    }
}
