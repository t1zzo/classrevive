package dev.classrevive.verification;

import dev.classrevive.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2CL: JADX's placeholder type {@code ??}, shaped like ZKM-obfuscated classes. Sources are only compiled. */
class PlaceholderTypesTest {
    @TempDir Path dir;
    static final List<ClassInfo> ORIGINALS = List.of(new ClassInfo("p/G", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
        List.of(new MemberInfo("<init>", "()V", 1))));
    static final String G = """
        package p;
        /* JADX WARN: Type inference failed for: r0v9, types: [java.lang.RuntimeException, java.lang.Throwable] */
        public class G {
            static boolean B() { return false; }
            static java.util.List<String> names() { return new java.util.ArrayList<>(); }
            void flag() {
                ?? B = B();
                if (B) { return; }
            }
            void reused() {
                ?? B = new int[1];
                B = new RuntimeException();
            }
            int count() {
                ?? names = names();
                ?? S = "ok";
                String text = "?? X = 1;";
                /* 0x006c: PHI (r0v41 ??) = (r0v54 ??) */
                return names.size() + S.length() + text.length();
            }
            double bits() {
                double d;
                d = 4613937818241073152;
                long l = 4613937818241073152;
                return d + l + 2147483648.5 + 0x7fffffffffffL;
            }
            int loop(java.util.List<String> list) {
                int n = 0;
                for (?? s : list) n += s.length();
                return n;
            }
        }
        """;

    @Test void placeholdersGetTheirInitializersTypeOrObjectAndOversizedLiteralsTheirTargetsForm() throws Exception {
        Path sources = Files.createDirectories(dir.resolve("jadx/p"));
        Files.writeString(sources.resolve("G.java"), G);
        assertTrue(PlaceholderTypes.present(G));
        assertFalse(PlaceholderTypes.present("class A { String s = \"?? X = 1;\"; /* ?? y; */ }"));
        var result = new PlaceholderTypes().type(dir.resolve("jadx"), dir.resolve("work"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(7, result.sites().size(), result.toString());
        var written = new TreeMap<Long,String>();
        for (var site : result.sites()) written.put(site.line(), site.written() + " " + site.rule());
        assertEquals(Map.of(7L, "boolean initializer", 11L, "Object object", 15L, "java.util.List<java.lang.String> initializer",
            16L, "java.lang.String initializer", 23L, "3.0 double-bits", 24L, "L long-literal", 29L, "java.lang.String initializer"), written);
        String typed = Files.readString(result.files().get("p/G.java"));
        assertTrue(typed.contains("boolean /* ?? */ B = B();"), typed);
        assertTrue(typed.contains("Object /* ?? */ B = new int[1];"), typed);
        assertTrue(typed.contains("String text = \"?? X = 1;\";"), typed);
        assertTrue(typed.contains("PHI (r0v41 ??) = (r0v54 ??)"), typed);
        assertTrue(typed.contains("d = 3.0;"), typed);
        assertTrue(typed.contains("long l = 4613937818241073152L;"), typed);
        assertTrue(typed.contains("for (java.lang.String /* ?? */ s : list)"), typed);
        assertTrue(PlaceholderTypes.present("class A { double d = 4613937818241073152; }"));
        assertFalse(PlaceholderTypes.present("class A { long a = 4613937818241073152L; int b = -2147483648; double c = 1.2345678901; }"));
        // The typed file compiles under the original release (Java 8), where `var` would not.
        Files.writeString(sources.resolve("G.java"), typed);
        var verification = new CompilerVerifier().verify(dir.resolve("jadx"), dir.resolve("check"), ORIGINALS, Duration.ofSeconds(60));
        assertEquals(VerificationResult.Status.COMPILED, verification.status(), verification.toString());
    }
}
