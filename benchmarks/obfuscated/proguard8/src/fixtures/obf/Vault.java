package fixtures.obf;

/** Holds XOR-masked literals; no static initializer so the static string pass may evaluate the decoder. */
final class Vault {
    private int reads;

    private static String unmask(String masked) {
        char[] chars = masked.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ 7);
        return new String(chars);
    }

    private static int bump(int value) {
        return value + 1;
    }

    String label() {
        reads = bump(reads);
        return unmask("kbc`bu*ubfc~");
    }

    String trail() {
        reads = bump(reads);
        return unmask("frcns'sufnk");
    }
}
