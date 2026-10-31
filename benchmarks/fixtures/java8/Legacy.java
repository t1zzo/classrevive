package fixtures;

import java.util.List;
import java.util.stream.Collectors;

public class Legacy {
    public String names(List<String> values) {
        return values.stream().filter(value -> !value.isEmpty()).map(String::trim).collect(Collectors.joining(","));
    }
    public int choose(int key) {
        switch (key) { case 1: return 10; case 7: return 20; default: return -1; }
    }
    public int parse(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException error) { return 0; }
    }
    public static class Nested { public String name() { return "nested"; } }
}
