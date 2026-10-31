package dev.classrevive.intake;

import dev.classrevive.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiReleaseTest {
    static Inventory.Entry entry(String path, String className) {
        var info = className == null ? null : new ClassInfo(className, "java/lang/Object", 53, 0, 1, List.of(), List.of(), List.of());
        return new Inventory.Entry(path, 1, "00", info);
    }
    static Inventory inventory(Inventory.Entry... entries) { return new Inventory("in.jar", "00", 1, 1, List.of(entries), List.of()); }

    @Test void acceptsOnlyVariantsWhosePathMatchesTheirIdentity() {
        var base = Set.of("p/A");
        var variant = MultiRelease.variant(entry("META-INF/versions/9/p/A.class", "p/A"), base);
        assertEquals(new MultiRelease.Variant("META-INF/versions/9/p/A.class", 9, "p/A", true), variant);
        var module = MultiRelease.variant(entry("META-INF/versions/11/module-info.class", "module-info"), base);
        assertEquals(11, module.release()); assertFalse(module.hasBase());
        // A path that does not name the class it holds is not a variant; decompile keeps rejecting it.
        assertNull(MultiRelease.variant(entry("META-INF/versions/9/p/B.class", "p/A"), base));
        assertNull(MultiRelease.variant(entry("META-INF/versions/9/q/p/A.class", "p/A"), base));
        // The JAR specification starts versioned directories at 9; leading zeros and non-numbers are not versions.
        assertNull(MultiRelease.variant(entry("META-INF/versions/8/p/A.class", "p/A"), base));
        assertNull(MultiRelease.variant(entry("META-INF/versions/09/p/A.class", "p/A"), base));
        assertNull(MultiRelease.variant(entry("META-INF/versions/x/p/A.class", "p/A"), base));
        assertNull(MultiRelease.variant(entry("META-INF/versions/9/p/A.txt", null), base));
        assertNull(MultiRelease.variant(entry("p/A.class", "p/A"), base));
    }
    @Test void listsVariantsAndWhetherTheBaseHasTheClass() {
        var result = MultiRelease.variants(inventory(entry("META-INF/versions/9/module-info.class", "module-info"),
            entry("META-INF/versions/9/p/A.class", "p/A"), entry("META-INF/versions/9/p/C.class", "p/Wrong"),
            entry("p/A.class", "p/A"), entry("readme.txt", null)));
        assertEquals(List.of(new MultiRelease.Variant("META-INF/versions/9/module-info.class", 9, "module-info", false),
            new MultiRelease.Variant("META-INF/versions/9/p/A.class", 9, "p/A", true)), result);
        assertTrue(MultiRelease.versioned("META-INF/versions/9/p/C.class"));
        assertFalse(MultiRelease.versioned("p/A.class"));
    }
}
