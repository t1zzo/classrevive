package dev.classrevive.verification;

public record ClassCoverage(String className, int expectedSymbols, int matchedSymbols) {
    public ClassCoverage {
        if (className == null || className.isBlank()) throw new IllegalArgumentException("className is required");
        if (expectedSymbols < 0 || matchedSymbols < 0 || matchedSymbols > expectedSymbols)
            throw new IllegalArgumentException("Invalid class signature counts");
    }

    public int coveragePermille() {
        return expectedSymbols == 0 ? 0 : (int) ((matchedSymbols * 1000L) / expectedSymbols);
    }

    public boolean complete() {
        return expectedSymbols > 0 && matchedSymbols == expectedSymbols;
    }
}
