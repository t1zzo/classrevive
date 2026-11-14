package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.engine.EngineResult;
import dev.classrevive.pipeline.JobListener;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Phase G3: a running decompile's stages and engines, live, with its log; the last run stays visible afterwards. Phase G8:
 * the stages are a timeline with their times right-aligned, the progress bar moves smoothly, and opening another input
 * clears the last run's stage times (they used to stay next to "No decompile yet").
 */
final class ActivityPanel extends JPanel {
    private enum State { PENDING, RUNNING, DONE, SKIPPED }

    private final Map<JobListener.Stage,StageRow> stages = new EnumMap<>(JobListener.Stage.class);
    private final Map<JobListener.Stage,State> states = new EnumMap<>(JobListener.Stage.class);
    private final JPanel engines = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Map<String,Ui.Chip> engineChips = new LinkedHashMap<>();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel headline = Ui.styled(Text.t("activity.idle"), "semibold");
    private final JLabel elapsed = Ui.muted("");
    private final JButton cancel;
    private final JTextArea log = new JTextArea();
    private final javax.swing.Timer clock;
    private Instant started;
    private final Map<JobListener.Stage,Instant> stageStarted = new EnumMap<>(JobListener.Stage.class);
    private final Map<JobListener.Stage,Duration> stageTook = new EnumMap<>(JobListener.Stage.class);
    private JobListener.Stage current;
    private javax.swing.Timer progressMotion;

    ActivityPanel(Action cancelAction) {
        super(new BorderLayout());
        var top = new JPanel(new BorderLayout(Ui.GAP, 4));
        top.setOpaque(false);
        top.setBorder(Ui.padding(8, 14, 6, 12));
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(headline);
        left.add(elapsed);
        top.add(left, BorderLayout.CENTER);
        // The engines get their own line, so a long headline never pushes them out of view.
        engines.setOpaque(false);
        engines.setBorder(Ui.padding(0, 4, 0, 0));
        top.add(engines, BorderLayout.SOUTH);
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setMaximum(JobListener.Stage.values().length * 100);
        progress.setPreferredSize(new Dimension(180, 6));
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Ui.GAP, 0));
        right.setOpaque(false);
        var bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        bar.add(progress);
        right.add(bar);
        cancel = new JButton(cancelAction);
        cancel.setIcon(Icons.of(Icons.Glyph.STOP, 12, Theme.level(ResultIndex.Level.UNVERIFIED)));
        cancel.setText(Text.t("toolbar.cancel"));
        cancel.setVisible(false);
        right.add(cancel);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        var steps = new JPanel(new GridLayout(0, 1, 0, 0));
        steps.setOpaque(false);
        steps.setBorder(Ui.padding(0, 14, 6, 14));
        var all = JobListener.Stage.values();
        for (int i = 0; i < all.length; i++) {
            var row = new StageRow(Text.t("stage." + all[i].name()), i == 0, i == all.length - 1);
            stages.put(all[i], row);
            states.put(all[i], State.PENDING);
            steps.add(row);
        }
        var stepsBox = new JPanel(new BorderLayout());
        stepsBox.setOpaque(false);
        stepsBox.add(steps, BorderLayout.NORTH);
        stepsBox.setPreferredSize(new Dimension(400, 150));
        log.setEditable(false);
        log.setFont(Theme.codeFont(12));
        log.setBorder(Ui.padding(8, 12, 8, 10));
        var logScroll = new JScrollPane(log);
        logScroll.setBorder(Ui.line(0, 1, 0, 0));
        add(stepsBox, BorderLayout.WEST);
        add(logScroll, BorderLayout.CENTER);
        paintStages();
        clock = new javax.swing.Timer(500, event -> tick());
        applyLook();
    }

    void start(String input, List<String> engineIds) {
        started = Instant.now();
        stageStarted.clear(); stageTook.clear(); current = null;
        moveProgress(0, false);
        for (var stage : JobListener.Stage.values()) states.put(stage, State.PENDING);
        engines.removeAll();
        engineChips.clear();
        for (String id : engineIds) {
            var chip = Ui.softChip(CodeTab.engineName(id) + " · " + Text.t("activity.engine.waiting"), Theme.muted());
            engineChips.put(id, chip);
            engines.add(chip);
        }
        engines.setVisible(true);
        engines.revalidate();
        headline.setText(Text.t("activity.running", Ui.visible(input)));
        progress.setIndeterminate(false);
        progress.setVisible(true);
        cancel.setVisible(true);
        cancel.setEnabled(true);
        paintStages();
        append(Text.t("activity.log.start", Ui.visible(input), String.join(", ", engineIds)));
        clock.start();
        tick();
    }

    /** Back to "no decompile yet" for a newly opened input; the log keeps its history. */
    void reset() { reset(null); }

    /**
     * Phase G8: back to a clean state for a newly opened input, with the last run's stage times cleared. {@code result} names
     * a result folder that was opened from disk (its decompile ran earlier), or is null for an input not decompiled yet.
     */
    void reset(String result) {
        if (clock.isRunning()) return;
        started = null;
        elapsed.setText("");
        stageStarted.clear();
        stageTook.clear();
        current = null;
        for (var stage : JobListener.Stage.values()) states.put(stage, State.PENDING);
        engines.removeAll();
        engineChips.clear();
        engines.revalidate();
        engines.setVisible(false);
        headline.setText(result == null ? Text.t("activity.idle") : Text.t("activity.opened", result));
        progress.setVisible(false);
        paintStages();
    }

    void stage(JobListener.Stage stage) {
        closeCurrent();
        for (var entry : states.entrySet()) {
            if (entry.getValue() == State.RUNNING) entry.setValue(State.DONE);
            if (entry.getKey().ordinal() < stage.ordinal() && entry.getValue() == State.PENDING) entry.setValue(State.SKIPPED);
        }
        states.put(stage, State.RUNNING);
        current = stage;
        stageStarted.put(stage, Instant.now());
        moveProgress(stage.ordinal() * 100 + 50, true);
        paintStages();
        append(Text.t("stage." + stage.name()));
    }

    void engineStarted(String engine) {
        var chip = engineChips.get(engine);
        if (chip != null) chip.set(CodeTab.engineName(engine) + " · " + Text.t("activity.engine.running"), Theme.ACCENT);
    }

    void engineFinished(EngineResult result) {
        var chip = engineChips.get(result.engine());
        boolean ok = result.status() == EngineResult.Status.OUTPUT_PRODUCED;
        if (chip != null) chip.set(CodeTab.engineName(result.engine()) + " · " + Text.t("engine." + result.status().name()),
            ok ? Theme.level(ResultIndex.Level.EXACT) : Theme.warning());
        append(Text.t("activity.log.engine", result.engine(), Text.t("engine." + result.status().name()), result.sourceFiles(), result.elapsedMillis()));
    }

    /** Phase G5: a step outside the pipeline stages (the Skidfuscator reversal), as headline and log line. */
    void note(String text) {
        headline.setText(text);
        append(text);
    }

    void cancelling() {
        cancel.setEnabled(false);
        headline.setText(Text.t("activity.cancelling"));
        append(Text.t("activity.cancelling"));
    }

    /** {@code outcome} is the headline; {@code success} marks every remaining stage done or skipped. */
    void finish(String outcome, boolean success) {
        closeCurrent();
        if (success) moveProgress(progress.getMaximum(), true);
        clock.stop();
        tick();
        for (var entry : states.entrySet()) {
            if (entry.getValue() == State.RUNNING) entry.setValue(success ? State.DONE : State.SKIPPED);
            else if (entry.getValue() == State.PENDING) entry.setValue(State.SKIPPED);
        }
        paintStages();
        headline.setText(outcome);
        progress.setIndeterminate(false);
        progress.setVisible(false);
        cancel.setVisible(false);
        append(outcome);
    }

    void append(String line) {
        log.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    /** Phase G8: colors that are set rather than read at paint time follow a theme switch. */
    void applyLook() {
        log.setBackground(Theme.editor());
        log.setForeground(Theme.secondary());
        log.setFont(Theme.codeFont(12));
        paintStages();
    }

    /** The progress bar glides to {@code value} instead of jumping (Phase G8). */
    private void moveProgress(int value, boolean animate) {
        if (progressMotion != null) progressMotion.stop();
        if (!animate) { progress.setValue(value); return; }
        int from = progress.getValue();
        progressMotion = Ui.animate(350, t -> progress.setValue((int) Math.round(from + (value - from) * t)), null);
    }

    /** The stage that was running ends now: its time is kept to show next to its name. */
    private void closeCurrent() {
        if (current != null && stageStarted.containsKey(current)) stageTook.put(current, Duration.between(stageStarted.get(current), Instant.now()));
        current = null;
    }

    private void tick() {
        if (started == null) return;
        long seconds = Duration.between(started, Instant.now()).toSeconds();
        String clockText = String.format("%d:%02d", seconds / 60, seconds % 60);
        elapsed.setText(current == null ? clockText : Text.t("activity.step", current.ordinal() + 1, JobListener.Stage.values().length, clockText));
    }

    /** "1.2 s" or "2 min 05 s"; empty before the stage ran. */
    static String took(Duration duration) {
        if (duration == null) return "";
        long millis = duration.toMillis();
        if (millis < 100) return "<0.1 s";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        return String.format(Locale.ROOT, "%d min %02d s", millis / 60_000, millis / 1000 % 60);
    }

    private void paintStages() {
        for (var stage : JobListener.Stage.values()) {
            var row = stages.get(stage);
            var state = states.get(stage);
            row.set(state, state == State.SKIPPED ? Text.t("activity.stage.skipped") : took(stageTook.get(stage)));
        }
    }

    /** For tests: the time shown next to a stage, empty when none. */
    String stageTime(JobListener.Stage stage) { return stages.get(stage).time.getText(); }
    String headlineText() { return headline.getText(); }

    /** One stage of the timeline: a marker on a vertical line, the stage's name, and its time on the right. */
    private static final class StageRow extends JPanel {
        private final JLabel name, time = Ui.faint("");
        private final boolean first, last;
        private State state = State.PENDING;

        StageRow(String text, boolean first, boolean last) {
            super(new BorderLayout(8, 0));
            this.first = first;
            this.last = last;
            setOpaque(false);
            setBorder(Ui.padding(1, 22, 1, 0));
            name = new JLabel(text);
            add(name, BorderLayout.CENTER);
            time.setHorizontalAlignment(SwingConstants.RIGHT);
            add(time, BorderLayout.EAST);
        }

        void set(State value, String timeText) {
            state = value;
            time.setText(timeText);
            name.putClientProperty(FlatClientProperties.STYLE, switch (value) {
                case RUNNING -> "font: $semibold.font; foreground: $Label.foreground";
                case DONE -> "foreground: $Label.foreground";
                case PENDING, SKIPPED -> "foreground: $ClassRevive.mutedForeground";
            });
            repaint();
        }

        @Override public Dimension getPreferredSize() { var size = super.getPreferredSize(); return new Dimension(size.width, Math.max(20, size.height)); }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = 7, cy = getHeight() / 2;
            g2.setColor(Theme.line());
            if (!first) g2.fillRect(cx - 1, 0, 2, cy - 6);
            if (!last) g2.fillRect(cx - 1, cy + 6, 2, getHeight() - cy - 6);
            switch (state) {
                case DONE -> {
                    g2.setColor(Theme.ACCENT);
                    g2.fillOval(cx - 6, cy - 6, 12, 12);
                    g2.setColor(Theme.ON_ACCENT);
                    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[] {cx - 3, cx - 1, cx + 3}, new int[] {cy, cy + 2, cy - 2}, 3);
                }
                case RUNNING -> {
                    g2.setColor(Theme.alpha(Theme.ACCENT, 70));
                    g2.fillOval(cx - 7, cy - 7, 14, 14);
                    g2.setColor(Theme.ACCENT);
                    g2.fillOval(cx - 4, cy - 4, 8, 8);
                }
                case PENDING -> {
                    g2.setColor(Theme.lineStrong());
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval(cx - 5, cy - 5, 10, 10);
                }
                case SKIPPED -> {
                    g2.setColor(Theme.line());
                    g2.fillOval(cx - 4, cy - 4, 8, 8);
                }
            }
            g2.dispose();
        }
    }
}
