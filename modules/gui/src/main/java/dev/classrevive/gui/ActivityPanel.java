package dev.classrevive.gui;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.pipeline.JobListener;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Phase G3: a running decompile's stages and engines, live, with its log; the last run stays visible afterwards. */
final class ActivityPanel extends JPanel {
    private enum State { PENDING, RUNNING, DONE, SKIPPED }

    private final Map<JobListener.Stage,JLabel> stages = new EnumMap<>(JobListener.Stage.class);
    private final Map<JobListener.Stage,State> states = new EnumMap<>(JobListener.Stage.class);
    private final JPanel engines = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Map<String,Ui.Chip> engineChips = new LinkedHashMap<>();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel headline = new JLabel(Text.t("activity.idle"));
    private final JLabel elapsed = Ui.muted("");
    private final JButton cancel;
    private final JTextArea log = new JTextArea();
    private final javax.swing.Timer clock;
    private Instant started;

    ActivityPanel(Action cancelAction) {
        super(new BorderLayout());
        var top = new JPanel(new BorderLayout(Ui.GAP, 0));
        top.setBorder(Ui.padding(8, 12, 6, 12));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD));
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP, 0));
        left.setOpaque(false);
        left.add(headline);
        left.add(elapsed);
        engines.setOpaque(false);
        left.add(engines);
        top.add(left, BorderLayout.WEST);
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(180, 6));
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Ui.GAP, 0));
        right.setOpaque(false);
        right.add(progress);
        cancel = new JButton(cancelAction);
        cancel.setVisible(false);
        right.add(cancel);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        var steps = new JPanel(new GridLayout(0, 1, 0, 2));
        steps.setBorder(Ui.padding(4, 14, 8, 12));
        for (var stage : JobListener.Stage.values()) {
            var label = new JLabel(Text.t("stage." + stage.name()));
            stages.put(stage, label);
            states.put(stage, State.PENDING);
            steps.add(label);
        }
        var stepsBox = new JPanel(new BorderLayout());
        stepsBox.add(steps, BorderLayout.NORTH);
        stepsBox.setPreferredSize(new Dimension(300, 150));
        log.setEditable(false);
        log.setFont(Theme.codeFont(12));
        log.setBorder(Ui.padding(6, 8, 6, 8));
        var logScroll = new JScrollPane(log);
        logScroll.setBorder(Ui.line(0, 1, 0, 0));
        add(stepsBox, BorderLayout.WEST);
        add(logScroll, BorderLayout.CENTER);
        paintStages();
        clock = new javax.swing.Timer(500, event -> tick());
    }

    void start(String input, List<String> engineIds) {
        started = Instant.now();
        for (var stage : JobListener.Stage.values()) states.put(stage, State.PENDING);
        engines.removeAll();
        engineChips.clear();
        for (String id : engineIds) {
            var chip = Ui.chip(id + " · " + Text.t("activity.engine.waiting"), Theme.muted());
            engineChips.put(id, chip);
            engines.add(chip);
        }
        engines.revalidate();
        headline.setText(Text.t("activity.running", Ui.visible(input)));
        progress.setIndeterminate(true);
        progress.setVisible(true);
        cancel.setVisible(true);
        cancel.setEnabled(true);
        paintStages();
        append(Text.t("activity.log.start", Ui.visible(input), String.join(", ", engineIds)));
        clock.start();
        tick();
    }

    /** Back to "no decompile yet" for a newly opened input; the log keeps its history. */
    void reset() {
        if (clock.isRunning()) return;
        started = null;
        elapsed.setText("");
        for (var stage : JobListener.Stage.values()) states.put(stage, State.PENDING);
        engines.removeAll();
        engineChips.clear();
        engines.revalidate();
        headline.setText(Text.t("activity.idle"));
        paintStages();
    }

    void stage(JobListener.Stage stage) {
        for (var entry : states.entrySet()) {
            if (entry.getValue() == State.RUNNING) entry.setValue(State.DONE);
            if (entry.getKey().ordinal() < stage.ordinal() && entry.getValue() == State.PENDING) entry.setValue(State.SKIPPED);
        }
        states.put(stage, State.RUNNING);
        paintStages();
        append(Text.t("stage." + stage.name()));
    }

    void engineStarted(String engine) {
        var chip = engineChips.get(engine);
        if (chip != null) chip.set(engine + " · " + Text.t("activity.engine.running"), Theme.ACCENT);
    }

    void engineFinished(EngineResult result) {
        var chip = engineChips.get(result.engine());
        boolean ok = result.status() == EngineResult.Status.OUTPUT_PRODUCED;
        if (chip != null) chip.set(result.engine() + " · " + Text.t("engine." + result.status().name()),
            ok ? Theme.level(dev.classrevive.workbench.ResultIndex.Level.CLOSE) : Theme.level(dev.classrevive.workbench.ResultIndex.Level.PARTIAL));
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

    private void tick() {
        if (started == null) return;
        long seconds = Duration.between(started, Instant.now()).toSeconds();
        elapsed.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
    }

    private void paintStages() {
        for (var stage : JobListener.Stage.values()) {
            var label = stages.get(stage);
            switch (states.get(stage)) {
                case PENDING -> { label.setIcon(ring(Theme.muted(), false)); label.setForeground(Theme.muted()); }
                case RUNNING -> { label.setIcon(ring(Theme.ACCENT, true)); label.setForeground(Theme.foreground()); }
                case DONE -> { label.setIcon(check()); label.setForeground(Theme.foreground()); }
                case SKIPPED -> { label.setIcon(ring(Theme.line(), false)); label.setForeground(Theme.muted()); }
            }
        }
    }

    private static Icon ring(Color color, boolean filled) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f));
                if (filled) g2.fillOval(x + 3, y + 3, 8, 8); else g2.drawOval(x + 3, y + 3, 8, 8);
                g2.dispose();
            }
            @Override public int getIconWidth() { return 14; }
            @Override public int getIconHeight() { return 14; }
        };
    }

    private static Icon check() {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.ACCENT);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawPolyline(new int[] {x + 3, x + 6, x + 11}, new int[] {y + 7, y + 10, y + 4}, 3);
                g2.dispose();
            }
            @Override public int getIconWidth() { return 14; }
            @Override public int getIconHeight() { return 14; }
        };
    }
}
