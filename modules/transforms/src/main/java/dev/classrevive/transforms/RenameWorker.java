package dev.classrevive.transforms;

import org.objectweb.asm.ClassReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reprocesses only the parent-validated snapshot. Never extracts, defines or executes target classes. */
public final class RenameWorker {
    private static final int ENTRY_LIMIT = 16 * 1024 * 1024;
    private static final long EXPANDED_LIMIT = 512L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) throw new IllegalArgumentException("Expected input, output and optional mapping");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        MappingFile.Plan plan = null;
        if (args.length == 3) plan = MappingFile.parse(readBounded(Files.newInputStream(Path.of(args[2]))));
        var classes = new TreeMap<String, byte[]>();
        var resources = new TreeMap<String, byte[]>();
        if (input.toString().endsWith(".class")) {
            byte[] bytes = readBounded(Files.newInputStream(input));
            classes.put(new ClassReader(bytes).getClassName(), bytes);
        } else {
            long total = 0;
            try (var zip = new ZipFile(input.toFile())) {
                var entries = zip.stream().limit(20001).toList();
                if (entries.size() > 20000) throw new IOException("Entry count limit exceeded");
                for (var entry : entries) {
                    String name = entry.getName();
                    if (name.startsWith("META-INF/versions/")) throw new IOException("Multi-release archives are unsupported");
                    byte[] bytes = readBounded(zip.getInputStream(entry));
                    total += bytes.length;
                    if (total > EXPANDED_LIMIT) throw new IOException("Expanded size limit exceeded");
                    if (name.toLowerCase(Locale.ROOT).endsWith(".class")) {
                        if (!name.endsWith(".class")) throw new IOException("Class entries must use the lowercase .class suffix");
                        String internal = new ClassReader(bytes).getClassName();
                        if (!name.equals(internal + ".class")) throw new IOException("Class entry path does not match its internal name");
                        // A member-only plan (Phase 2BW) moves no class, so descriptors pass through unchanged.
                        boolean membersOnly = plan != null && plan.classes().isEmpty();
                        if (!membersOnly && (internal.equals("module-info") || internal.equals("package-info") || internal.endsWith("/package-info")))
                            throw new IOException("Module and package descriptors are unsupported");
                        classes.put(internal, bytes);
                    } else {
                        resources.put(name, bytes);
                    }
                }
            }
        }
        var result = plan == null
            ? new ClassRenamer().rename(classes, ClassRenamer.plan(classes.keySet()), true)
            : new ClassRenamer().rename(classes, plan.classes(), plan.members(), true);
        var outputs = new TreeMap<String, byte[]>();
        for (var entry : resources.entrySet()) {
            String name = entry.getKey(); byte[] bytes = entry.getValue();
            if (name.startsWith("META-INF/services/")) {
                var service = ArchiveMetadata.rewriteServices(name, bytes, result.forward());
                name = service.name(); bytes = service.bytes();
                if (outputs.containsKey(name)) throw new IOException("Service entry collision");
            }
            outputs.put(name, bytes);
        }
        if (outputs.containsKey("META-INF/MANIFEST.MF"))
            outputs.put("META-INF/MANIFEST.MF", ArchiveMetadata.rewriteManifest(outputs.get("META-INF/MANIFEST.MF"), result.forward()));
        for (var entry : result.classes().entrySet()) {
            if (outputs.putIfAbsent(entry.getKey() + ".class", entry.getValue()) != null)
                throw new IOException("Renamed class path collides with an existing entry");
        }
        var seen = new HashSet<String>();
        for (String name : outputs.keySet()) if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Output entry collision");
        try (var zip = new ZipOutputStream(Files.newOutputStream(output.resolve("renamed.jar"), StandardOpenOption.CREATE_NEW))) {
            for (var entry : outputs.entrySet()) {
                var item = new ZipEntry(entry.getKey()); item.setTime(0);
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        var audit = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(audit)) {
            data.writeInt(3); data.writeInt(result.forward().size());
            for (var entry : result.forward().entrySet()) { data.writeUTF(entry.getKey()); data.writeUTF(entry.getValue()); }
            data.writeInt(result.members().size());
            for (var entry : result.members().entrySet()) {
                var key = entry.getKey();
                data.writeUTF(key.owner()); data.writeUTF(key.name()); data.writeUTF(key.descriptor());
                data.writeBoolean(key.field()); data.writeUTF(entry.getValue());
            }
            data.writeInt(result.reflectionRewrites());
        }
        if (audit.size() > 16 * 1024 * 1024) throw new IOException("Mapping evidence exceeds limit");
        Files.write(output.resolve("map.bin"), audit.toByteArray(), StandardOpenOption.CREATE_NEW);
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        try (stream) {
            byte[] bytes = stream.readNBytes(ENTRY_LIMIT + 1);
            if (bytes.length > ENTRY_LIMIT) throw new IOException("Entry size limit exceeded");
            return bytes;
        }
    }
}
