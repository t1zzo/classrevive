package app;
public class Main {
    public static void main(String[] args) throws Exception {
        Player player = new Player("x", 1);
        Team team = new Team();
        team.enroll(player);
        System.out.println(player.describe() + team.label());
    }
}
