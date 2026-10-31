package dev.classrevive.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase 2CC: ClassRevive's own static reader for the headers of a bundled native library (PE, ELF, Mach-O format only).
 * The bytes are data: every offset is bounds-checked, nothing is mapped, loaded or run. It gives the sections with their
 * entropy, the exported and imported names and packing hints even when Ghidra is not configured.
 */
public final class NativeBinary {
    private NativeBinary() {}

    /** {@code permissions} is {@code rwx} style; {@code entropyMillibits} is Shannon entropy of the file data times 1000, -1 without data. */
    public record Section(String name, long virtualAddress, long virtualSize, long rawSize, String permissions, int entropyMillibits) {
        public boolean executable() { return permissions.indexOf('x') >= 0; }
        public boolean unnamed() { return name.isBlank(); }
    }
    public record Import(String library, String name) {}
    /** Header facts; {@code entryPoint} is an RVA (PE) or virtual address (ELF), -1 when unknown. */
    public record Headers(String format, String machine, int bits, long entryPoint, List<Section> sections, List<String> exports,
                          List<Import> imports, List<String> neededLibraries, boolean tls, List<String> problems) {
        public Headers {
            sections = List.copyOf(sections); exports = List.copyOf(exports); imports = List.copyOf(imports);
            neededLibraries = List.copyOf(neededLibraries); problems = List.copyOf(problems);
        }
        public boolean known() { return !format.equals("unknown"); }
        public Optional<Section> entrySection() {
            if (entryPoint < 0) return Optional.empty();
            return sections.stream().filter(s -> entryPoint >= s.virtualAddress()
                && entryPoint < s.virtualAddress() + Math.max(s.virtualSize(), s.rawSize())).findFirst();
        }
    }
    /** {@code verdict}: {@code LIKELY_PACKED} or {@code NOT_DETECTED}; hints are readable reasons, packers are hypotheses. */
    public record Packing(String verdict, List<String> hints, List<String> suspectedPackers) {
        public Packing { hints = List.copyOf(hints); suspectedPackers = List.copyOf(suspectedPackers); }
        public boolean packed() { return verdict.equals("LIKELY_PACKED"); }
    }

    static final int MAX_SECTIONS = 96, MAX_NAMES = 5000, MAX_LIBRARIES = 256;
    /** Executable sections at or above this entropy (bits per byte, times 1000) look compressed or encrypted. */
    static final int HIGH_ENTROPY = 7200;

    public static Headers parse(byte[] bytes) {
        try {
            if (bytes.length >= 64 && bytes[0] == 'M' && bytes[1] == 'Z') return pe(bytes);
            if (bytes.length >= 52 && (bytes[0] & 0xff) == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') return elf(bytes);
            if (bytes.length >= 8) {
                long magic = u32(bytes, 0, false);
                if (magic == 0xfeedfaceL || magic == 0xfeedfacfL || magic == 0xcefaedfeL || magic == 0xcffaedfeL)
                    return machO(bytes, magic == 0xcefaedfeL || magic == 0xcffaedfeL);
                if (magic == 0xcafebabeL && u32(bytes, 4, true) < 64) return simple("Mach-O universal", "multiple", 0, "fat binary; slices not parsed");
            }
        } catch (IndexOutOfBoundsException truncated) {
            return simple("unknown", "unknown", 0, "truncated or malformed headers");
        }
        return simple("unknown", "unknown", 0, "not a PE, ELF or Mach-O file");
    }

    private static Headers simple(String format, String machine, int bits, String problem) {
        return new Headers(format, machine, bits, -1, List.of(), List.of(), List.of(), List.of(), false, List.of(problem));
    }

    private static Headers pe(byte[] b) {
        var problems = new ArrayList<String>();
        int pe = (int) u32(b, 0x3c, true);
        if (pe < 0 || pe > b.length - 24 || b[pe] != 'P' || b[pe + 1] != 'E' || b[pe + 2] != 0 || b[pe + 3] != 0)
            return simple("unknown", "unknown", 0, "MZ header without a PE signature");
        int machine = u16(b, pe + 4, true), count = u16(b, pe + 6, true), optionalSize = u16(b, pe + 20, true);
        int optional = pe + 24, magic = u16(b, optional, true);
        boolean plus = magic == 0x20b;
        if (!plus && magic != 0x10b) return simple("unknown", "unknown", 0, "unknown PE optional header magic");
        long entry = u32(b, optional + 16, true);
        int directories = optional + (plus ? 112 : 96);
        long directoryCount = u32(b, optional + (plus ? 108 : 92), true);
        if (count > MAX_SECTIONS) { problems.add(count + " sections; only " + MAX_SECTIONS + " read"); count = MAX_SECTIONS; }
        var sections = new ArrayList<Section>();
        int table = optional + optionalSize;
        var raw = new ArrayList<long[]>();
        for (int i = 0; i < count; i++) {
            int at = table + 40 * i;
            if (at + 40 > b.length) { problems.add("section table truncated"); break; }
            String name = ascii(b, at, 8);
            long virtualSize = u32(b, at + 8, true), address = u32(b, at + 12, true), rawSize = u32(b, at + 16, true), pointer = u32(b, at + 20, true);
            long flags = u32(b, at + 36, true);
            String permissions = ((flags & 0x40000000L) != 0 ? "r" : "-") + ((flags & 0x80000000L) != 0 ? "w" : "-") + ((flags & 0x20000000L) != 0 ? "x" : "-");
            sections.add(new Section(name, address, virtualSize, rawSize, permissions, entropy(b, pointer, rawSize)));
            raw.add(new long[] {address, Math.max(virtualSize, rawSize), pointer, rawSize});
        }
        var exports = new ArrayList<String>();
        var imports = new ArrayList<Import>();
        var libraries = new ArrayList<String>();
        boolean tls = false;
        try {
            if (directoryCount > 0) {
                long rva = u32(b, directories, true), size = u32(b, directories + 4, true);
                int at = offset(raw, rva);
                if (size > 0 && at >= 0) {
                    long names = Math.min(u32(b, at + 24, true), MAX_NAMES);
                    int nameTable = offset(raw, u32(b, at + 32, true));
                    for (int i = 0; i < names && nameTable >= 0; i++) {
                        int name = offset(raw, u32(b, nameTable + 4 * i, true));
                        if (name >= 0) exports.add(cString(b, name, 512));
                    }
                }
            }
            if (directoryCount > 1) {
                int at = offset(raw, u32(b, directories + 8, true));
                for (int d = 0; at >= 0 && d < MAX_LIBRARIES; d++, at += 20) {
                    long lookup = u32(b, at, true), nameRva = u32(b, at + 12, true), first = u32(b, at + 16, true);
                    if (lookup == 0 && nameRva == 0 && first == 0) break;
                    int nameAt = offset(raw, nameRva);
                    String library = nameAt < 0 ? "?" : cString(b, nameAt, 256);
                    libraries.add(library);
                    int thunk = offset(raw, lookup != 0 ? lookup : first);
                    for (int t = 0; thunk >= 0 && imports.size() < MAX_NAMES; t++) {
                        long value = plus ? u64(b, thunk + 8L * t) : u32(b, thunk + 4 * t, true);
                        if (value == 0) break;
                        boolean ordinal = plus ? value < 0 : (value & 0x80000000L) != 0;
                        if (ordinal) { imports.add(new Import(library, "#" + (value & 0xffff))); continue; }
                        int hint = offset(raw, value & 0x7fffffffL);
                        imports.add(new Import(library, hint < 0 ? "?" : cString(b, hint + 2, 256)));
                    }
                }
            }
            if (directoryCount > 9) tls = u32(b, directories + 72, true) != 0 && u32(b, directories + 76, true) != 0;
        } catch (IndexOutOfBoundsException truncated) {
            problems.add("export or import directory truncated");
        }
        String arch = switch (machine) {
            case 0x14c -> "x86"; case 0x8664 -> "x86-64"; case 0xaa64 -> "AArch64"; case 0x1c4 -> "ARM (Thumb-2)";
            default -> String.format(Locale.ROOT, "machine 0x%04x", machine);
        };
        return new Headers(plus ? "PE32+" : "PE32", arch, plus ? 64 : 32, entry, sections, exports, imports, libraries, tls, problems);
    }

    private static int offset(List<long[]> sections, long rva) {
        if (rva <= 0) return -1;
        for (long[] s : sections) {
            if (rva >= s[0] && rva < s[0] + s[1]) {
                long delta = rva - s[0];
                if (delta >= s[3]) return -1;
                long at = s[2] + delta;
                return at > Integer.MAX_VALUE ? -1 : (int) at;
            }
        }
        return -1;
    }

    private static Headers elf(byte[] b) {
        var problems = new ArrayList<String>();
        boolean wide = b[4] == 2, little = b[5] != 2;
        int machine = u16(b, 18, little);
        long entry = wide ? u64le(b, 24, little) : u32(b, 24, little);
        long sectionOffset = wide ? u64le(b, 40, little) : u32(b, 32, little);
        int entrySize = u16(b, wide ? 58 : 46, little), count = u16(b, wide ? 60 : 48, little), names = u16(b, wide ? 62 : 50, little);
        if (count > MAX_SECTIONS * 2) { problems.add(count + " sections; only " + MAX_SECTIONS * 2 + " read"); count = MAX_SECTIONS * 2; }
        var raw = new ArrayList<long[]>(); // name, type, flags, address, offset, size, link, entsize
        for (int i = 0; i < count; i++) {
            long at = sectionOffset + (long) entrySize * i;
            if (sectionOffset <= 0 || at + (wide ? 64 : 40) > b.length) { if (sectionOffset > 0) problems.add("section table truncated"); break; }
            int s = (int) at;
            raw.add(wide
                ? new long[] {u32(b, s, little), u32(b, s + 4, little), u64le(b, s + 8, little), u64le(b, s + 16, little), u64le(b, s + 24, little),
                    u64le(b, s + 32, little), u32(b, s + 40, little), u64le(b, s + 56, little)}
                : new long[] {u32(b, s, little), u32(b, s + 4, little), u32(b, s + 8, little), u32(b, s + 12, little), u32(b, s + 16, little),
                    u32(b, s + 20, little), u32(b, s + 24, little), u32(b, s + 36, little)});
        }
        long[] nameTable = names < raw.size() ? raw.get(names) : null;
        var sections = new ArrayList<Section>();
        var exports = new ArrayList<String>();
        var imports = new ArrayList<Import>();
        var needed = new ArrayList<String>();
        for (long[] s : raw) {
            if (s[1] == 0) continue; // SHT_NULL
            String name = nameTable == null ? "" : stringAt(b, nameTable, s[0]);
            boolean nobits = s[1] == 8;
            String permissions = ((s[2] & 2) != 0 ? "r" : "-") + ((s[2] & 1) != 0 ? "w" : "-") + ((s[2] & 4) != 0 ? "x" : "-");
            if (sections.size() < MAX_SECTIONS)
                sections.add(new Section(name, s[3], s[5], nobits ? 0 : s[5], permissions, nobits ? -1 : entropy(b, s[4], s[5])));
            if (s[1] == 11 && s[6] < raw.size()) { // SHT_DYNSYM, linked string table
                long[] strings = raw.get((int) s[6]);
                long size = wide ? 24 : 16;
                for (long at = s[4] + size; at + size <= s[4] + s[5] && at + size <= b.length; at += size) {
                    int e = (int) at;
                    long nameOffset = u32(b, e, little);
                    int info = b[e + (wide ? 4 : 12)] & 0xff, index = u16(b, e + (wide ? 6 : 14), little);
                    int bind = info >> 4, type = info & 0xf;
                    if (nameOffset == 0 || (bind != 1 && bind != 2)) continue;
                    String symbol = stringAt(b, strings, nameOffset);
                    if (index != 0 && type == 2 && exports.size() < MAX_NAMES) exports.add(symbol);
                    else if (index == 0 && (type == 2 || type == 0) && imports.size() < MAX_NAMES) imports.add(new Import("", symbol));
                }
            }
            if (s[1] == 6 && s[6] < raw.size()) { // SHT_DYNAMIC: DT_NEEDED names
                long[] strings = raw.get((int) s[6]);
                long size = wide ? 16 : 8;
                for (long at = s[4]; at + size <= s[4] + s[5] && at + size <= b.length && needed.size() < MAX_LIBRARIES; at += size) {
                    long tag = wide ? u64le(b, (int) at, little) : u32(b, (int) at, little);
                    if (tag == 0) break;
                    if (tag == 1) needed.add(stringAt(b, strings, wide ? u64le(b, (int) at + 8, little) : u32(b, (int) at + 4, little)));
                }
            }
        }
        String arch = switch (machine) {
            case 3 -> "x86"; case 62 -> "x86-64"; case 183 -> "AArch64"; case 40 -> "ARM"; case 8 -> "MIPS"; case 20 -> "PowerPC";
            case 21 -> "PowerPC64"; case 22 -> "S390"; case 243 -> "RISC-V"; case 258 -> "LoongArch"; case 2 -> "SPARC";
            default -> "machine " + machine;
        };
        return new Headers(wide ? "ELF64" : "ELF32", arch, wide ? 64 : 32, entry, sections, exports, imports, needed, false, problems);
    }

    /** {@code little}: the magic read big-endian was cefaedfe/cffaedfe, so the file is little-endian. */
    private static Headers machO(byte[] b, boolean little) {
        long cpu = u32(b, 4, little);
        boolean wide = (cpu & 0x01000000L) != 0;
        String arch = switch ((int) (cpu & 0xffffff)) {
            case 7 -> wide ? "x86-64" : "x86"; case 12 -> wide ? "AArch64" : "ARM"; case 18 -> wide ? "PowerPC64" : "PowerPC";
            default -> "cpu " + cpu;
        };
        return new Headers(wide ? "Mach-O 64" : "Mach-O", arch, wide ? 64 : 32, -1, List.of(), List.of(), List.of(), List.of(), false,
            List.of("Mach-O load commands are not parsed by ClassRevive; Ghidra reads them"));
    }

    /** Packing hints from header facts only (names, sizes, entropy, imports). A hypothesis, never proof. */
    public static Packing packing(Headers headers) {
        var hints = new ArrayList<String>();
        var packers = new LinkedHashSet<String>();
        boolean strong = false;
        long unnamed = headers.sections().stream().filter(Section::unnamed).count();
        if (unnamed >= 2) { hints.add(unnamed + " sections without a name"); strong = true; }
        for (var section : headers.sections()) {
            String name = section.name().trim().toLowerCase(Locale.ROOT);
            String packer = packerSection(name);
            if (packer != null) { hints.add("section " + section.name().trim() + " (" + packer + " name)"); packers.add(packer); strong = true; }
            if (section.executable() && section.rawSize() == 0 && section.virtualSize() > 0) {
                hints.add("executable section " + label(section) + " has no file data (" + section.virtualSize() + " bytes filled at run time)");
                strong = true;
            }
            if (section.executable() && section.rawSize() >= 4096 && section.entropyMillibits() >= HIGH_ENTROPY) {
                hints.add("executable section " + label(section) + " has entropy " + bits(section.entropyMillibits()) + " bits/byte (compressed or encrypted)");
                strong = true;
            }
        }
        headers.entrySection().ifPresent(section -> {
            var executable = headers.sections().stream().filter(Section::executable).findFirst();
            if (executable.isPresent() && executable.get() != section) hints.add("entry point in section " + label(section) + ", not the first code section");
        });
        if (headers.format().startsWith("PE") && headers.neededLibraries().size() >= 5
            && headers.imports().size() <= headers.neededLibraries().size() * 3 / 2) {
            hints.add(headers.imports().size() + " imports from " + headers.neededLibraries().size()
                + " libraries (one or two each: the rest resolved at run time)");
            strong = true;
        }
        if (headers.tls() && strong) hints.add("TLS callback directory present (code that runs before the entry point)");
        boolean boot = headers.sections().stream().anyMatch(s -> s.name().trim().equalsIgnoreCase(".boot"));
        var suspected = new ArrayList<String>(packers);
        if (unnamed >= 2 && boot) { suspected.remove("Themida/WinLicense"); suspected.addFirst("Themida/WinLicense 3.x"); }
        return new Packing(strong ? "LIKELY_PACKED" : "NOT_DETECTED", hints, suspected);
    }

    private static String packerSection(String name) {
        if (name.equals(".boot") || name.equals(".themida") || name.equals(".winlice")) return "Themida/WinLicense";
        if (name.matches("\\.vmp[0-9]")) return "VMProtect";
        if (name.equals(".vlizer")) return "Oreans Code Virtualizer"; // Phase 2CE
        if (name.matches("\\.?upx[0-9!]?")) return "UPX";
        if (name.equals(".aspack") || name.equals(".adata")) return "ASPack";
        if (name.matches("\\.mpress[12]")) return "MPRESS";
        if (name.matches("\\.enigma[12]")) return "Enigma Protector";
        if (name.equals(".petite")) return "Petite";
        if (name.matches("\\.nsp[012]")) return "NsPack";
        if (name.equals("pec1") || name.equals("pec2") || name.equals(".pec")) return "PECompact";
        return null;
    }

    static String label(Section section) { return section.unnamed() ? "(unnamed @0x" + Long.toHexString(section.virtualAddress()) + ")" : section.name().trim(); }
    static String bits(int millibits) { return String.format(Locale.ROOT, "%d.%02d", millibits / 1000, (millibits % 1000) / 10); }

    /** Shannon entropy of {@code bytes[offset, offset + length)} clipped to the file, times 1000; -1 when empty. */
    static int entropy(byte[] bytes, long offset, long length) {
        if (offset < 0 || offset >= bytes.length || length <= 0) return -1;
        int end = (int) Math.min(bytes.length, offset + length);
        long[] counts = new long[256];
        for (int i = (int) offset; i < end; i++) counts[bytes[i] & 0xff]++;
        double total = end - offset, bits = 0;
        for (long count : counts) if (count > 0) { double p = count / total; bits -= p * (Math.log(p) / Math.log(2)); }
        return (int) Math.round(bits * 1000);
    }

    private static String stringAt(byte[] b, long[] table, long offset) {
        long at = table[4] + offset;
        if (offset < 0 || offset >= table[5] || at >= b.length) return "?";
        return cString(b, (int) at, 512);
    }
    private static String cString(byte[] b, int at, int max) {
        int end = at;
        while (end < b.length && end - at < max && b[end] != 0) end++;
        return clean(new String(b, at, end - at, StandardCharsets.ISO_8859_1));
    }
    private static String ascii(byte[] b, int at, int length) {
        int end = at;
        while (end < at + length && b[end] != 0) end++;
        return clean(new String(b, at, end - at, StandardCharsets.ISO_8859_1));
    }
    /** Names are untrusted: anything outside printable ASCII becomes \\xNN so every report line stays one line. */
    static String clean(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7f) out.append(c); else out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
        }
        return out.toString();
    }
    private static int u16(byte[] b, int at, boolean little) {
        return little ? (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 : (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }
    private static long u32(byte[] b, int at, boolean little) {
        long v = 0;
        for (int i = 0; i < 4; i++) v |= (long) (b[at + i] & 0xff) << (little ? 8 * i : 8 * (3 - i));
        return v;
    }
    private static long u64(byte[] b, long at) { return u64le(b, Math.toIntExact(at), true); }
    private static long u64le(byte[] b, int at, boolean little) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (long) (b[at + i] & 0xff) << (little ? 8 * i : 8 * (7 - i));
        return v;
    }
}
