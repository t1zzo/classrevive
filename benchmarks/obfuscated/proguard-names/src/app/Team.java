package app;
import java.util.*;
public class Team {
    private final List<Player> members = new ArrayList<>();
    private final Map<String,Player> byName = new HashMap<>();
    private String title;
    private void addMember(Player player) { members.add(player); }
    private String getTitle() { return title; }
    public void enroll(Player player) { addMember(player); byName.put("x", player); }
    public String label() { return getTitle(); }
    @Override public String toString() { return "Team[title=" + title + "]"; }
}
