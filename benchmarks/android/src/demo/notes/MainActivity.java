package demo.notes;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    private final NoteStore store = new NoteStore();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String[] seed = { "Groceries|high|milk eggs bread", "Ideas|low|a dex fixture", "broken line" };
        int loaded = store.load(seed);
        setTitle("Notes (" + loaded + ")");
    }

    public NoteStore store() { return store; }
}
