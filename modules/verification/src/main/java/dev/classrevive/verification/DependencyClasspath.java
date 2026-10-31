package dev.classrevive.verification;

import dev.classrevive.intake.InventoryReader;
import dev.classrevive.intake.MultiRelease;
import dev.classrevive.model.Limits;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;

/** Produces local class-only snapshots; manifests cannot expand javac's dependency search. */
final class DependencyClasspath {
    /**
     * Phase 2DF: the 16 JARs a user may pass, plus the two the pipeline adds itself (an Android SDK's android.jar and the
     * inferred-stub JAR), so neither is dropped when the user's 16 are all given. Callers still cap user JARs at 16.
     */
    static final int MAX_INPUTS = 18;
    static List<Path> prepare(List<Path> inputs, Path output, Set<String> originals) throws IOException {
        inputs=List.copyOf(inputs);
        if(inputs.size()>MAX_INPUTS) throw new IllegalArgumentException("At most 16 dependency JARs, plus the Android SDK and inferred stubs");
        if(inputs.isEmpty()) return List.of();
        Files.createDirectory(output);
        var paths=new ArrayList<Path>(); var names=new HashSet<String>(originals);
        var evidence=new StringBuilder("Class-only compiler dependencies; no target execution.\n");
        long total=0, expanded=0;
        for(int i=0;i<inputs.size();i++) {
            Path input=dev.classrevive.engine.SafePaths.resolveParents(inputs.get(i));
            if(!input.toString().toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Expected regular dependency JAR");
            Path snapshot=output.resolve(i+"-input.jar");
            try(var in=Files.newInputStream(input); var out=Files.newOutputStream(snapshot,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[8192]; int count;
                while((count=in.read(buffer))!=-1) { total+=count; if(total>128L*1024*1024) throw new IOException("Dependency byte budget exceeded"); out.write(buffer,0,count); }
            }
            var inventory=new InventoryReader().inspect(snapshot,Limits.defaults());
            if(!inventory.successful()) throw new IOException("Invalid dependency inventory");
            expanded+=inventory.expandedBytes();
            if(expanded>512L*1024*1024) throw new IOException("Expanded dependency budget exceeded");
            Path normalized=output.resolve(i+"-classes.jar"); int classes=0;
            // Multi-release dependencies contribute their base classes only; valid META-INF/versions/N variants are dropped.
            var variants=new HashSet<String>(); MultiRelease.variants(inventory).forEach(variant->variants.add(variant.entry()));
            try(var zip=new ZipFile(snapshot.toFile()); var out=new ZipOutputStream(Files.newOutputStream(normalized,StandardOpenOption.CREATE_NEW))) {
                for(var entry:inventory.entries()) {
                    if(entry.classInfo()==null || variants.contains(entry.name())) continue;
                    String name=entry.classInfo().name();
                    // These are classpath inputs, not a module path: descriptors are not dependency types.
                    if(name.equals("module-info") && entry.name().equals("module-info.class")) continue;
                    if(!entry.name().equals(name+".class") || !names.add(name))
                        throw new IOException("Dependency class overlap, identity mismatch or invalid multi-release variant: "+entry.name());
                    var item=new ZipEntry(entry.name()); item.setTime(0); out.putNextEntry(item);
                    try(var in=zip.getInputStream(zip.getEntry(entry.name()))) { in.transferTo(out); }
                    out.closeEntry(); classes++;
                }
            }
            if(classes==0) throw new IOException("Dependency contains no classes");
            evidence.append(i).append(" sha256=").append(inventory.sha256()).append(" classes=").append(classes);
            if(!variants.isEmpty()) evidence.append(" multi-release-variants-ignored=").append(variants.size());
            evidence.append('\n');
            paths.add(normalized.toAbsolutePath());
        }
        Files.writeString(output.resolve("dependencies.txt"),evidence,StandardOpenOption.CREATE_NEW);
        return List.copyOf(paths);
    }
}
