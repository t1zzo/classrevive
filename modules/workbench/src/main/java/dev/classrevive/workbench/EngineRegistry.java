package dev.classrevive.workbench;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.jadx.JadxEngine;
import dev.classrevive.procyon.ProcyonEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.util.*;

/** The bundled decompiler engines, in their fixed order, and the {@code cfr,vineflower,...} / {@code all} selection syntax. */
public final class EngineRegistry {
    private EngineRegistry() {}

    public static List<DecompilerEngine> all() { return List.of(new CfrEngine(), new VineflowerEngine(), new ProcyonEngine(), new JadxEngine()); }

    public static List<DecompilerEngine> parse(String names) {
        if (names.equals("all")) return all();
        var result = new ArrayList<DecompilerEngine>();
        var seen = new HashSet<String>();
        for (String name : names.split(",", -1)) {
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate engine: " + name);
            result.add(all().stream().filter(engine -> engine.id().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown engine: " + name + ". Use cfr,vineflower,procyon,jadx or all.")));
        }
        return List.copyOf(result);
    }
}
