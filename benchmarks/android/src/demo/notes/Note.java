package demo.notes;

import demo.util.Strings;

public final class Note {
    private final String title;
    private final String body;
    private final Priority priority;
    private int edits;

    public Note(String title, String body, Priority priority) {
        this.title = Strings.trimToEmpty(title);
        this.body = body == null ? "" : body;
        this.priority = priority;
    }

    public String title() { return title; }
    public String body() { return body; }
    public Priority priority() { return priority; }
    public int edits() { return edits; }

    public Note edit(String newBody) {
        Note next = new Note(title, newBody, priority);
        next.edits = edits + 1;
        return next;
    }

    public int words() {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < body.length(); i++) {
            boolean letter = !Character.isWhitespace(body.charAt(i));
            if (letter && !inWord) count++;
            inWord = letter;
        }
        return count;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Note)) return false;
        Note note = (Note) other;
        return title.equals(note.title) && body.equals(note.body) && priority == note.priority;
    }

    @Override public int hashCode() { return 31 * (31 * title.hashCode() + body.hashCode()) + priority.hashCode(); }

    @Override public String toString() { return "Note[" + title + ", " + priority + ", " + words() + " words]"; }
}
