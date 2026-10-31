package android.app;

/** Compile-time stand-in for the Android framework class; never put in the fixture's dex. */
public class Activity {
    protected void onCreate(android.os.Bundle saved) { }
    public void setTitle(CharSequence title) { }
}
