package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.LatencyClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects what one bench run measured and writes it where {@code tools/perf} can read it.
 *
 * <p><b>It does no statistics, deliberately.</b> Every lane of M6.7b writes this same shape and one
 * Python file works all three out: raw samples here, raw samples off the phone's log, and one
 * implementation of median, spread and outlier-hunting in {@code tools/perf/perfstat.py}. Two
 * implementations of a percentile is how a before-and-after comparison ends up comparing two
 * different questions.
 *
 * <p>It also writes no summary of its own for the same reason, and prints only enough to show that
 * a scenario ran.
 *
 * <p>Files land in {@code perf-runs/<label>/<screen>-<arm>/<scenario>.json}.
 *
 * <p>⚠ <b>NOT under {@code target/}, and that is a repair.</b> They were, and {@code mvn clean}
 * deleted every baseline this milestone had taken, which is exactly the failure the directory
 * invites: {@code target/} holds what a build produces and is meant to be thrown away, while a
 * baseline exists precisely to be compared against a <em>later</em> build. {@code perf-runs/} is
 * git-ignored, so the files stay out of the repository and survive a clean.
 *
 * <p>The label names the
 * state of the code being measured ({@code baseline}, {@code after-x}), passed in with
 * {@code -Dinvmgr.perf.label=}.
 *
 * <h2>⚠ Two arms, and reading only one of them is how an optimization looks worthless</h2>
 *
 * <p><b>felt</b> is the app as it ships: JavaFX draws at most 60 frames a second, so an event waits
 * for the next one. That is the number a hand feels and it is the honest answer to "how long until
 * I see it". <b>It is also blind.</b> Measured on Xvfb, every scenario in a small room came back at
 * a median of 24 ms, from a one-key search to a 2,310-sample slider drag: half a frame of waiting
 * plus a whole frame of pulse, and the app's own work lost inside it. Work below the cap cannot
 * show up at all, which is M6.7a's ThreeDFrameProbe lesson arriving one level up.
 *
 * <p><b>work</b> is the same run with {@code -Djavafx.animation.fullspeed=true}, a property read
 * out of the shipped {@code javafx-graphics-21-linux.jar} rather than remembered. The cap goes and
 * the same six scenarios came back at 1.1 to 1.6 ms, spread out by how much each actually does, and
 * a 40 ms spike appeared that the capped arm had swallowed whole. That is the arm an optimization
 * has to move.
 *
 * <p>Neither is the real one. A change that helps only the second arm helps nobody today and helps
 * every slower machine tomorrow; a change that moves the first arm is felt immediately.
 */
final class PerfRun {

    /** What to call this run if nobody said. Not "baseline": that name has to be chosen. */
    private static final String DEFAULT_LABEL = "unlabeled";

    private final String label;
    private final String platform;
    private final Path root;
    private final List<LatencyClock.Sample> samples =
            Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> repOfSample = Collections.synchronizedList(new ArrayList<>());
    private int rep;

    PerfRun() {
        this.label = System.getProperty("invmgr.perf.label", DEFAULT_LABEL);
        this.platform = System.getProperty("invmgr.perf.platform", guessPlatform()) + "-" + arm();
        this.root = Path.of("perf-runs", label, platform);
    }

    /**
     * Which screen this is, when nobody said.
     *
     * <p>{@code DISPLAY} is the only thing that tells the two desktop lanes apart from in here, and
     * getting them mixed up would quietly compare a software rasterizer against a graphics card.
     * {@code :99} is the Xvfb the project's build instructions specify; anything else is taken to be
     * the real session.
     */
    private static String guessPlatform() {
        String display = System.getenv("DISPLAY");
        return ":99".equals(display) ? "xvfb" : "plasma";
    }

    /**
     * Which of the two arms this run is, worked out from the property that makes the difference.
     *
     * <p>Asked of the running JVM rather than passed in, so the folder cannot say {@code work}
     * while the toolkit is still capped. That property has to be on the command line to be read at
     * all: the toolkit picks it up as it starts, long before any test code runs.
     */
    private static String arm() {
        return Boolean.getBoolean("javafx.animation.fullspeed") ? "work" : "felt";
    }

    String label() {
        return label;
    }

    String platform() {
        return platform;
    }

    /** Hands this to {@link LatencyClock#installFor}: every sample is kept, tagged with its rep. */
    void accept(LatencyClock.Sample sample) {
        samples.add(sample);
        repOfSample.add(rep);
    }

    /** Throws away everything so far and starts a scenario's repetitions at zero. */
    void begin() {
        samples.clear();
        repOfSample.clear();
        rep = 0;
    }

    /** Marks the start of the next repetition. Call before each one, not after. */
    void nextRep(int which) {
        rep = which;
    }

    /**
     * Writes what has been collected, as JSON, and says where it went.
     *
     * <p>Hand-written rather than through a library: §8 and OD-3 make a JSON dependency a decision,
     * the app already has its own codec for the save format, and nothing here needs escaping beyond
     * numbers and a handful of fixed words.
     *
     * @param scenario the name this run goes under, which is also the file name
     * @param notes one line saying what the scenario did, for whoever reads the file later
     */
    void write(String scenario, String notes) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n");
        json.append("  \"scenario\": \"").append(scenario).append("\",\n");
        json.append("  \"platform\": \"").append(platform).append("\",\n");
        json.append("  \"label\": \"").append(label).append("\",\n");
        json.append("  \"notes\": \"").append(notes.replace("\"", "'")).append("\",\n");
        json.append("  \"written\": ").append(System.currentTimeMillis()).append(",\n");
        json.append("  \"samples\": [\n");
        synchronized (samples) {
            for (int i = 0; i < samples.size(); i++) {
                LatencyClock.Sample s = samples.get(i);
                json.append(String.format(
                        "    {\"rep\": %d, \"seq\": %d, \"type\": \"%s\", "
                                + "\"q\": %.3f, \"r\": %.3f, \"t\": %.3f, \"ms\": %d}%s%n",
                        repOfSample.get(i), s.seq(), s.type(),
                        s.queueMs(), s.renderMs(), s.totalMs(), s.wallMs(),
                        i == samples.size() - 1 ? "" : ","));
            }
        }
        json.append("  ]\n}\n");
        try {
            Files.createDirectories(root);
            Path out = root.resolve(scenario + ".json");
            Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
            System.out.printf("PERF  %-22s %4d samples -> %s%n", scenario, samples.size(), out);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + scenario, e);
        }
    }

    /**
     * Writes a list of plain durations under one made-up event type.
     *
     * <p>For the things that are not an input event at all: the frames of a flight into the 3D
     * view, and the whole-window draw. They belong in the same files as everything else so one
     * reporter covers the lot, and they go in as {@code total} with the other two columns zero,
     * which the reporter shows as blank rather than as a fast queue.
     *
     * @param type what these durations are, which the reporter prints as-is
     * @param millis the durations
     */
    void writeDurations(String scenario, String notes, String type, List<Double> millis) {
        begin();
        long now = System.currentTimeMillis();
        for (int i = 0; i < millis.size(); i++) {
            samples.add(new LatencyClock.Sample(i, type, 0, 0, millis.get(i), now));
            repOfSample.add(i);
        }
        write(scenario, notes);
    }
}
