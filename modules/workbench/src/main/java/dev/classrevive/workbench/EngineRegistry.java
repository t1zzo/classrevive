package dev.classrevive.workbench;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.jadx.JadxEngine;
import dev.classrevive.jdcore.JdCoreEngine;
import dev.classrevive.procyon.ProcyonEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.util.*;

/** The bundled decompiler engines, in their fixed order, and the {@code cfr,vineflower,...} / {@code all} selection syntax. */
public final class EngineRegistry {
    private EngineRegistry() {}

    public static List<DecompilerEngine> all() { return List.of(new CfrEngine(), new VineflowerEngine(), new ProcyonEngine(), new JadxEngine()); }

    public static List<DecompilerEngine> parse(String names) { return parse(names, System.getenv()); }

    /**
     * Phase 2DO: {@code jd-core} is an optional engine the user installs. It is accepted only when named explicitly and
     * when {@code CLASSREVIVE_JDCORE_JAR} points to its jar; {@code all} stays the four bundled engines.
     */
    static List<DecompilerEngine> parse(String names, Map<String,String> environment) {
        if (names.equals("all")) return all();
        var result = new ArrayList<DecompilerEngine>();
        var seen = new HashSet<String>();
        for (String name : names.split(",", -1)) {
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate engine: " + name);
            if (name.equals(JdCoreEngine.ID)) {
                result.add(JdCoreEngine.locate(environment).orElseThrow(() -> new IllegalArgumentException(
                    "jd-core is optional and not installed: set " + JdCoreEngine.VARIABLE + " to your JD-Core jar (GPL-3.0, never bundled)")));
                continue;
            }
            result.add(all().stream().filter(engine -> engine.id().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown engine: " + name + ". Use cfr,vineflower,procyon,jadx or all"
                    + " (jd-core when " + JdCoreEngine.VARIABLE + " is set).")));
        }
        return List.copyOf(result);
    }

    /** Phase 2DP: one line on the optional JD-Core engine for {@code classrevive engines}: its version, or how to install it. */
    public static String optionalStatus(Map<String,String> environment) {
        try {
            return JdCoreEngine.locate(environment).map(engine -> JdCoreEngine.ID + " " + engine.version() + " (optional, from "
                    + JdCoreEngine.VARIABLE + "; kept only when no worse)")
                .orElse(JdCoreEngine.ID + " not installed (optional: set " + JdCoreEngine.VARIABLE + " to your JD-Core jar, GPL-3.0, never bundled)");
        } catch (IllegalArgumentException unusable) {
            return JdCoreEngine.ID + " unusable: " + unusable.getMessage();
        }
    }
}
