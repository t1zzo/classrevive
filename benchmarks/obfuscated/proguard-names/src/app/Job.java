package app;
public class Job implements Runnable {
    private int counter;
    private long started;
    private void tick() { counter++; }
    @Override public void run() { started = System.nanoTime(); tick(); }
}
