package app;
import java.util.*;
public class Item {
    private final String label;
    private double price;
    private int quantity;
    public Item(String label, double price, int quantity) { this.label = label; this.price = price; this.quantity = quantity; }
    private double getPrice() { return price; }
    private boolean isEmpty() { return quantity == 0; }
    public void export(Map<String,Object> json) { json.put("label", label); json.put("unit_price", price); json.put("quantity", quantity); }
    public String check() { return isEmpty() ? "empty" : "price " + getPrice(); }
    @Override public String toString() { return "Item(label=" + label + ")"; }
}
