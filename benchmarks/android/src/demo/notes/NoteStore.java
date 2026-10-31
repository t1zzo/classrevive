package demo.notes;

import android.util.Log;
import java.util.*;

public class NoteStore {
    private static final String TAG = "NoteStore";
    private final Map<String, Note> notes = new LinkedHashMap<String, Note>();

    public static final class Stats {
        public final int count;
        public final int words;
        Stats(int count, int words) { this.count = count; this.words = words; }
    }

    public synchronized void put(Note note) {
        if (note == null) throw new IllegalArgumentException("note");
        notes.put(note.title(), note);
        Log.d(TAG, "stored " + note.title());
    }

    public synchronized Note get(String title) { return notes.get(title); }

    public List<Note> byPriority() {
        List<Note> sorted = new ArrayList<Note>(notes.values());
        Collections.sort(sorted, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                int byWeight = b.priority().weight() - a.priority().weight();
                return byWeight != 0 ? byWeight : a.title().compareTo(b.title());
            }
        });
        return sorted;
    }

    public Stats stats() {
        int words = 0;
        for (Note note : notes.values()) words += note.words();
        return new Stats(notes.size(), words);
    }

    public int load(String[] lines) {
        int loaded = 0;
        for (String line : lines) {
            String[] parts = line.split("\\|", 3);
            try {
                put(new Note(parts[0], parts.length > 2 ? parts[2] : "", Priority.parse(parts[1])));
                loaded++;
            } catch (ArrayIndexOutOfBoundsException malformed) {
                Log.w(TAG, "skipped: " + line);
            }
        }
        return loaded;
    }
}
