package app;
import java.util.*;
public class Player {
    private final String name;
    private int score;
    private final List<Item> items = new ArrayList<>();
    private Team team;
    public Player(String name, int score) { this.name = Objects.requireNonNull(name, "name"); this.score = score; }
    private String getName() { return name; }
    private int getScore() { return score; }
    private void setScore(int score) { this.score = score; }
    private void addItem(Item item) { items.add(item); }
    private void setTeam(Team team) { this.team = team; }
    public String describe() { return getName() + getScore() + team; }
    public void grant(Item item, int points) { addItem(item); setScore(getScore() + points); }
    public void join(Team squad) { setTeam(squad); }
    @Override public String toString() { return "Player{name=" + name + ", score=" + score + ", items=" + items + "}"; }
}
