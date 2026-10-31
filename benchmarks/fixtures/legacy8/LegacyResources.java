package fixtures;

import java.io.*;
import java.util.*;

public class LegacyResources {
    public int first(byte[] input) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(input)) { return stream.read(); }
    }
    public List<String> copy(Collection<String> input) { return new ArrayList<String>(input); }
    public int code(String value) {
        switch (value) { case "on": return 1; case "off": return 0; default: return -1; }
    }
}
