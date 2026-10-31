package fixtures;

public class Patterns {
    public String describe(Object value) {
        return switch (value) {
            case null -> "null";
            case Integer number -> "int:" + number;
            case String text when text.isEmpty() -> "empty";
            case String text -> text;
            default -> "other";
        };
    }
}
