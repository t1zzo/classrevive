package fixtures.aids;

/** An interface hierarchy, instanceof checks, switches on int, char and String, and numeric constants of every width. */
public final class Shapes {
    public interface Shape {
        double area();
    }

    public static final class Circle implements Shape {
        private final double radius;
        public Circle(double radius) { this.radius = radius; }
        public double area() { return Math.PI * radius * radius; }
    }

    public static final class Rect implements Shape {
        private final int width;
        private final int height;
        public Rect(int width, int height) { this.width = width; this.height = height; }
        public double area() { return width * height; }
        public int perimeter() { return 2 * (width + height); }
    }

    private Shapes() {}

    public static String describe(Object value) {
        if (value instanceof Circle) {
            return "circle";
        } else if (value instanceof Rect) {
            Rect rect = (Rect) value;
            return "rect/" + rect.perimeter();
        } else if (value instanceof Shape) {
            return "shape";
        }
        return "unknown";
    }

    public static int weight(int code) {
        switch (code) {
            case 1: return 10;
            case 2: return 20;
            case 3: return 35;
            case 7: return 70;
            case 100: return 1000;
            default: return -1;
        }
    }

    public static int vowel(char c) {
        switch (Character.toLowerCase(c)) {
            case 'a': return 1;
            case 'e': return 2;
            case 'i': return 3;
            case 'o': return 4;
            case 'u': return 5;
            default: return 0;
        }
    }

    public static int command(String name) {
        switch (name) {
            case "start": return 1;
            case "stop": return 2;
            case "pause": return 3;
            default: return 0;
        }
    }

    public static boolean isAdmin(String user) {
        return "root".equals(user) || user.equalsIgnoreCase("Administrator");
    }

    public static long mix(byte b, short s, char c, int i, long l) {
        int bits = (b & 0xFF) | (s << 8) | (c << 16);
        long wide = l ^ 0x5DEECE66DL;
        return wide + bits + i * 31 + 65536 - 127;
    }

    public static int[] table() {
        return new int[] {3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5};
    }
}
