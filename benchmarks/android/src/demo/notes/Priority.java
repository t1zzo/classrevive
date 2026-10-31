package demo.notes;

public enum Priority {
    LOW(1), NORMAL(2), HIGH(3);

    private final int weight;

    Priority(int weight) { this.weight = weight; }

    public int weight() { return weight; }

    public static Priority parse(String text) {
        switch (text.toLowerCase()) {
            case "low": return LOW;
            case "high": case "urgent": return HIGH;
            default: return NORMAL;
        }
    }
}
