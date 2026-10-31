// ClassRevive phase 2CC: post-analysis export for one native library (static triage only).
// Run by analyzeHeadless as -postScript ClassReviveNativeExport.java <output.json> <max-strings> <max-decompiled> <max-c-bytes>.
// It only reads the program Ghidra loaded and analyzed statically: nothing is loaded into a process, run, emulated or
// unpacked. The parent (dev.classrevive.pipeline.NativeReport) reads the JSON strictly; integers only, no fractions.
//@category ClassRevive

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.util.headless.HeadlessScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.ExternalLocation;
import ghidra.program.model.symbol.ExternalLocationIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ClassReviveNativeExport extends HeadlessScript {
    private static final int MAX_IMPORTS = 5000, MAX_EXPORTS = 5000, MAX_SITES = 200, MAX_TABLE_ENTRIES = 2000, MAX_STRING_CHARS = 200;
    private static final long MAX_ENTROPY_BYTES = 64L * 1024 * 1024;
    private static final Pattern DESCRIPTOR = Pattern.compile("\\((\\[*([BCDFIJSZ]|L[A-Za-z0-9_/$]+;))*\\)(V|\\[*([BCDFIJSZ]|L[A-Za-z0-9_/$]+;))");
    /** JNINativeInterface slot 215 is RegisterNatives; 216 UnregisterNatives. Offsets by pointer size. */
    private static final int REGISTER_NATIVES_SLOT = 215;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 4) throw new IllegalArgumentException("Expected <output.json> <max-strings> <max-decompiled> <max-c-bytes>");
        File output = new File(args[0]);
        int maxStrings = Integer.parseInt(args[1]), maxDecompiled = Integer.parseInt(args[2]), maxCBytes = Integer.parseInt(args[3]);
        var report = new LinkedHashMap<String,Object>();
        report.put("schema_version", 1L);
        report.put("tool", "ghidra");
        report.put("ghidra_version", getGhidraVersion());
        report.put("program", currentProgram.getName());
        report.put("format", currentProgram.getExecutableFormat());
        report.put("language", currentProgram.getLanguageID().getIdAsString());
        report.put("compiler", currentProgram.getCompilerSpec().getCompilerSpecID().getIdAsString());
        report.put("pointer_size", (long) currentProgram.getDefaultPointerSize());
        report.put("image_base", currentProgram.getImageBase().toString());
        report.put("sha256", String.valueOf(currentProgram.getExecutableSHA256()));
        report.put("analyzed", currentProgram.getOptions("Program Information").getBoolean("Analyzed", false));
        report.put("analysis_timed_out", isRunningHeadless() && analysisTimeoutOccurred());
        report.put("function_count", (long) currentProgram.getFunctionManager().getFunctionCount());
        report.put("blocks", blocks());
        report.put("imports", imports());
        var exports = exports();
        report.put("exports", exports);
        var jniFunctions = new ArrayList<Function>();

        for (var row : exports) {
            String name = (String) row.get("name");
            // 32-bit Windows and Mach-O decorate C names: _Java_p_A_m@12, _JNI_OnLoad@8.
            String plain = name.startsWith("_J") ? name.substring(1) : name;
            if (plain.indexOf('@') > 0) plain = plain.substring(0, plain.indexOf('@'));
            boolean jni = plain.startsWith("Java_") || plain.startsWith("JNI_OnLoad") || plain.equals("JNI_OnUnload");
            Address address = toAddr((String) row.get("address"));
            Function function = getFunctionAt(address);
            if (function == null && jni) function = createAt(address);
            if (function == null) continue;
            if (jniFunctions.contains(function)) continue;
            if (plain.startsWith("Java_") || plain.equals("JNI_OnUnload")) jniFunctions.add(function);
            if (plain.startsWith("JNI_OnLoad")) jniFunctions.add(0, function);
        }
        report.put("jni_functions", functions(jniFunctions));
        report.put("register_natives_sites", registerNativesSites(jniFunctions));
        var strings = new ArrayList<Map<String,Object>>();
        var descriptors = new ArrayList<Data>();
        strings(maxStrings, strings, descriptors);
        report.put("strings", strings);
        report.put("native_method_tables", nativeMethodTables(descriptors));
        report.put("decompiled", decompile(jniFunctions, maxDecompiled, maxCBytes));
        Files.writeString(output.toPath(), json(report), StandardCharsets.UTF_8);
        println("ClassRevive native export written: " + output);
    }

    private List<Map<String,Object>> blocks() throws Exception {
        var rows = new ArrayList<Map<String,Object>>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            var row = new LinkedHashMap<String,Object>();
            row.put("name", block.getName());
            row.put("start", block.getStart().toString());
            row.put("size", block.getSize());
            row.put("permissions", (block.isRead() ? "r" : "-") + (block.isWrite() ? "w" : "-") + (block.isExecute() ? "x" : "-"));
            row.put("initialized", block.isInitialized());
            row.put("entropy_millibits", block.isInitialized() && block.getSize() > 0 ? entropy(block) : -1L);
            rows.add(row);
        }
        return rows;
    }

    private long entropy(MemoryBlock block) throws Exception {
        long[] counts = new long[256]; long total = 0;
        byte[] buffer = new byte[65536];
        long limit = Math.min(block.getSize(), MAX_ENTROPY_BYTES);
        Address address = block.getStart();
        while (total < limit) {
            monitor.checkCancelled();
            int want = (int) Math.min(buffer.length, limit - total);
            int read = block.getBytes(address.add(total), buffer, 0, want);
            if (read <= 0) break;
            for (int i = 0; i < read; i++) counts[buffer[i] & 0xff]++;
            total += read;
        }
        if (total == 0) return -1;
        double bits = 0;
        for (long count : counts) if (count > 0) { double p = (double) count / total; bits -= p * (Math.log(p) / Math.log(2)); }
        return Math.round(bits * 1000);
    }

    private List<Map<String,Object>> imports() {
        var rows = new ArrayList<Map<String,Object>>();
        var externals = currentProgram.getExternalManager();
        for (String library : externals.getExternalLibraryNames()) {
            ExternalLocationIterator locations = externals.getExternalLocations(library);
            while (locations.hasNext() && rows.size() < MAX_IMPORTS) {
                ExternalLocation location = locations.next();
                var row = new LinkedHashMap<String,Object>();
                row.put("library", library);
                row.put("name", location.getLabel());
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String,Object>> exports() {
        var rows = new ArrayList<Map<String,Object>>();
        SymbolTable symbols = currentProgram.getSymbolTable();
        AddressIterator entries = symbols.getExternalEntryPointIterator();
        var seen = new HashSet<String>();
        while (entries.hasNext() && rows.size() < MAX_EXPORTS) {
            Address address = entries.next();
            Symbol[] atAddress = symbols.getSymbols(address);
            for (Symbol symbol : atAddress) {
                if (symbol.isExternal() || !seen.add(symbol.getName() + "@" + address)) continue;
                // PE exports by name also carry an Ordinal_N label at the same address; keep it only when it is the only name.
                if (atAddress.length > 1 && symbol.getName().matches("Ordinal_[0-9]+")) continue;
                var row = new LinkedHashMap<String,Object>();
                row.put("name", symbol.getName());
                row.put("address", address.toString());
                row.put("function", getFunctionAt(address) != null);
                rows.add(row);
            }
        }
        return rows;
    }

    private final Set<Address> created = new HashSet<>();

    /**
     * Static disassembly at a JNI export that auto-analysis left without a function (packed code is often not followed).
     * Only bytes already in the file are decoded; nothing is emulated. Null when the address holds no initialized code.
     */
    private Function createAt(Address address) {
        try {
            MemoryBlock block = currentProgram.getMemory().getBlock(address);
            if (block == null || !block.isInitialized() || !block.isExecute()) return null;
            disassemble(address);
            Function function = createFunction(address, null);
            if (function != null) created.add(address);
            return function;
        } catch (Exception undecodable) {
            return null;
        }
    }

    private List<Map<String,Object>> functions(List<Function> functions) {
        var rows = new ArrayList<Map<String,Object>>();
        for (Function function : functions) {
            var row = new LinkedHashMap<String,Object>();
            row.put("name", function.getName());
            row.put("address", function.getEntryPoint().toString());
            row.put("size", function.getBody().getNumAddresses());
            row.put("signature", function.getSignature().getPrototypeString());
            var called = new ArrayList<Object>();
            for (Function callee : function.getCalledFunctions(monitor)) {
                if (called.size() == 50) break;
                called.add(callee.getName() + "@" + callee.getEntryPoint());
            }
            row.put("calls", called);
            row.put("created_by_script", created.contains(function.getEntryPoint()));
            rows.add(row);
        }
        return rows;
    }

    /** Instructions in JNI_OnLoad, the Java_* functions and their callees (3 levels, 300 functions) that use the JNIEnv RegisterNatives offset. */
    private List<Map<String,Object>> registerNativesSites(List<Function> roots) throws Exception {
        long offset = (long) REGISTER_NATIVES_SLOT * currentProgram.getDefaultPointerSize();
        var rows = new ArrayList<Map<String,Object>>();
        var queue = new ArrayDeque<Object[]>();
        for (Function root : roots) queue.add(new Object[] {root, 0});
        Set<Address> visited = new HashSet<>();
        while (!queue.isEmpty() && visited.size() < 300 && rows.size() < MAX_SITES) {
            monitor.checkCancelled();
            Object[] next = queue.poll(); Function function = (Function) next[0]; int depth = (Integer) next[1];
            if (function.isExternal() || function.isThunk() || !visited.add(function.getEntryPoint())) continue;
            InstructionIterator instructions = currentProgram.getListing().getInstructions(function.getBody(), true);
            while (instructions.hasNext() && rows.size() < MAX_SITES) {
                Instruction instruction = instructions.next();
                boolean match = false;
                for (int i = 0; i < instruction.getNumOperands() && !match; i++)
                    for (Object operand : instruction.getOpObjects(i))
                        if (operand instanceof Scalar scalar && scalar.getUnsignedValue() == offset) match = true;
                if (!match) continue;
                var row = new LinkedHashMap<String,Object>();
                row.put("function", function.getName());
                row.put("address", instruction.getAddress().toString());
                row.put("instruction", instruction.toString());
                row.put("depth", (long) depth);
                rows.add(row);
            }
            if (depth < 3) for (Function callee : function.getCalledFunctions(monitor)) queue.add(new Object[] {callee, depth + 1});
        }
        return rows;
    }

    private void strings(int max, List<Map<String,Object>> rows, List<Data> descriptors) throws Exception {
        DataIterator data = currentProgram.getListing().getDefinedData(true);
        int seen = 0;
        while (data.hasNext() && seen < 200_000) {
            monitor.checkCancelled();
            Data item = data.next(); seen++;
            if (!item.hasStringValue()) continue;
            Object value = item.getValue();
            if (!(value instanceof String text) || text.isEmpty()) continue;
            if (DESCRIPTOR.matcher(text).matches() && descriptors.size() < 512) descriptors.add(item);
            if (rows.size() >= max) continue;
            var row = new LinkedHashMap<String,Object>();
            row.put("address", item.getAddress().toString());
            row.put("type", item.getDataType().getName());
            row.put("length", (long) text.length());
            row.put("value", text.length() > MAX_STRING_CHARS ? text.substring(0, MAX_STRING_CHARS) : text);
            rows.add(row);
        }
    }

    /**
     * JNINativeMethod {name, signature, fnPtr} arrays: for every string that is a JVM method descriptor, initialized memory
     * is searched for a pointer to it; the pointer before must point to a name string and the one after into executable code.
     */
    private List<Map<String,Object>> nativeMethodTables(List<Data> descriptors) throws Exception {
        var rows = new ArrayList<Map<String,Object>>();
        Memory memory = currentProgram.getMemory();
        int size = currentProgram.getDefaultPointerSize();
        if (size != 4 && size != 8) return rows;
        boolean big = currentProgram.getLanguage().isBigEndian();
        for (Data descriptor : descriptors) {
            byte[] pattern = pointer(descriptor.getAddress().getOffset(), size, big);
            Address from = memory.getMinAddress();
            int hits = 0;
            while (from != null && hits < 64 && rows.size() < MAX_TABLE_ENTRIES) {
                monitor.checkCancelled();
                Address hit = memory.findBytes(from, pattern, null, true, monitor);
                if (hit == null) break;
                hits++;
                from = hit.next();
                try {
                    Address nameAddress = toAddr(read(memory, hit.subtract(size), size, big));
                    Address code = toAddr(read(memory, hit.add(size), size, big));
                    String name = cString(memory, nameAddress);
                    MemoryBlock block = memory.getBlock(code);
                    if (name == null || block == null || !block.isExecute()) continue;
                    var row = new LinkedHashMap<String,Object>();
                    row.put("entry", hit.subtract(size).toString());
                    row.put("name", name);
                    row.put("descriptor", (String) descriptor.getValue());
                    row.put("function_address", code.toString());
                    Function function = getFunctionAt(code);
                    row.put("function", function == null ? "" : function.getName());
                    rows.add(row);
                } catch (Exception notATable) {
                    // An address outside memory or an unreadable pointer: not a JNINativeMethod entry.
                }
            }
        }
        return rows;
    }

    private static byte[] pointer(long value, int size, boolean big) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[big ? size - 1 - i : i] = (byte) (value >>> (8 * i));
        return bytes;
    }

    private static long read(Memory memory, Address address, int size, boolean big) throws Exception {
        byte[] bytes = new byte[size];
        if (memory.getBytes(address, bytes) != size) throw new IllegalStateException("short read");
        long value = 0;
        for (int i = 0; i < size; i++) value |= (long) (bytes[big ? size - 1 - i : i] & 0xff) << (8 * i);
        return value;
    }

    /** A NUL-terminated printable ASCII name of 1..256 characters, or null. */
    private static String cString(Memory memory, Address address) {
        var out = new StringBuilder();
        try {
            for (int i = 0; i < 257; i++) {
                int b = memory.getByte(address.add(i)) & 0xff;
                if (b == 0) return out.length() == 0 ? null : out.toString();
                if (b < 0x20 || b > 0x7e) return null;
                out.append((char) b);
            }
        } catch (Exception unreadable) {
            return null;
        }
        return null;
    }

    private List<Map<String,Object>> decompile(List<Function> functions, int max, int maxBytes) {
        var rows = new ArrayList<Map<String,Object>>();
        if (max <= 0 || functions.isEmpty()) return rows;
        var decompiler = new DecompInterface();
        try {
            if (!decompiler.openProgram(currentProgram)) return rows;
            for (Function function : functions) {
                if (rows.size() >= max || monitor.isCancelled()) break;
                var row = new LinkedHashMap<String,Object>();
                row.put("name", function.getName());
                row.put("address", function.getEntryPoint().toString());
                DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
                String code = result != null && result.decompileCompleted() && result.getDecompiledFunction() != null
                    ? result.getDecompiledFunction().getC() : null;
                row.put("completed", code != null);
                if (code == null) row.put("error", result == null ? "no result" : String.valueOf(result.getErrorMessage()));
                else {
                    row.put("truncated", code.length() > maxBytes);
                    row.put("c", code.length() > maxBytes ? code.substring(0, maxBytes) : code);
                }
                rows.add(row);
            }
        } finally {
            decompiler.dispose();
        }
        return rows;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) return value.toString();
        if (value instanceof Map<?,?> map) {
            var out = new StringBuilder("{");
            for (var entry : map.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(quote((String) entry.getKey())).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            var out = new StringBuilder("[");
            for (Object item : list) { if (out.length() > 1) out.append(','); out.append(json(item)); }
            return out.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
