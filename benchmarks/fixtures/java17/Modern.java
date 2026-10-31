package fixtures;

public record Modern(String name, int count) {
    public String describe(int kind) {
        return switch (kind) { case 1 -> name + ": " + count; default -> "unknown"; };
    }
    public boolean named(Object other) { return other instanceof String text && text.equals(name); }
}
