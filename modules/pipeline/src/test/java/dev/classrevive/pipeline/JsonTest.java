package dev.classrevive.pipeline;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void escapesControlsQuotesAndSurrogates() {
        assertEquals("\"a\\n\\t\\\"\\\\\\u0000\\ud800\"", Json.encode("a\n\t\"\\\0\ud800"));
    }
    @Test void sortsKeysAndHandlesNestedValues() {
        var values = new HashMap<String,Object>();
        values.put("z", null); values.put("a", List.of(true, 12, "ok"));
        assertEquals("{\"a\":[true,12,\"ok\"],\"z\":null}", Json.encode(values));
    }
    @Test void decodesWhatItEncodes() {
        var values = new TreeMap<String,Object>();
        values.put("text", "a\n\t\"\\\0\ud800/é"); values.put("list", List.of(true, false, -12L, List.of(), Map.of()));
        values.put("none", null); values.put("nested", Map.of("k", 9_000_000_000L));
        assertEquals(values, Json.decode(Json.encode(values)));
        assertEquals(List.of("x"), Json.decode(" [ \"\\u0078\" ] "));
    }
    @Test void decodingIsStrict() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\":1,\"a\":2}", "1.5", "1e3", "01x", "\"open", "[1] 2", "nul", "{a:1}",
                "\"\\q\"", "\"tab\there\"", "-", "99999999999999999999"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(bad), bad);
        assertThrows(IllegalArgumentException.class, () -> Json.decode("[".repeat(100) + "]".repeat(100)));
    }
    @Test void rejectsNonFiniteOrUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> Json.encode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.encode(new Object()));
    }
}
