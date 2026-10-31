package fixtures.aids;

import java.util.function.Function;

/** Entry point: lambdas, method references, nested try/catch and a static initializer. */
public class Main {
    static final String GREETING;
    private static int calls;

    static {
        GREETING = "ledger v" + 2;
    }

    public static void main(String[] args) {
        Ledger ledger = new Ledger("ana");
        ledger.add("bread", Kind.FOOD, 350);
        ledger.add("flat", Kind.RENT, 120000);
        ledger.parseAndAdd("misc", " 42 ");
        ledger.parseAndAdd("bad", "x");
        try {
            ledger.add("broken", Kind.OTHER, -5);
        } catch (IllegalArgumentException e) {
            System.out.println("rejected: " + e.getMessage());
        }
        Function<Long, String> money = cents -> cents / 100 + "." + cents % 100;
        System.out.println(GREETING + " " + ledger.owner() + " " + money.apply(ledger.total(null)));
        ledger.labels().forEach(System.out::println);
        System.out.println(Shapes.describe(new Shapes.Rect(2, 3)) + " " + Shapes.weight(7) + " " + Shapes.command("pause"));
        System.out.println(count() + " " + Kind.fromCode("rent").essential());
    }

    static int count() {
        return ++calls;
    }
}
