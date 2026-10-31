package dev.classrevive.transforms;

import org.objectweb.asm.ClassReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Accepts only the parent-validated snapshot. Never extracts or loads classes. */
public final class TransformWorker {
    public static void main(String[] args) throws Exception {
        if(args.length!=4) throw new IllegalArgumentException("Expected input, output, budget and passes");
        Path input=Path.of(args[0]), output=Path.of(args[1]); int budget=Integer.parseInt(args[2]);
        if(budget<1 || budget>10000) throw new IllegalArgumentException("Budget must be 1..10000");
        var changes=new ArrayList<TransformResult.Change>();
        var jarPasses=BuiltinPasses.jarPasses(args[3]);
        BuiltinPasses.names(args[3]); // validate every name before any input is read
        // Every entry is read first: whole-jar passes (Phase 2CN) and whole-program passes (Phase 2CM) need every class.
        var entries=new LinkedHashMap<String,byte[]>();
        if(input.toString().endsWith(".class")) {
            byte[] bytes=readBounded(Files.newInputStream(input),16*1024*1024);
            entries.put(new ClassReader(bytes).getClassName()+".class",bytes);
        } else {
            try(var source=new ZipFile(input.toFile())) {
                var list=source.stream().limit(20001).toList();
                if(list.size()>20000) throw new IOException("Entry count limit exceeded");
                long total=0;
                for(var entry:list) {
                    byte[] bytes=readBounded(source.getInputStream(entry),16*1024*1024);
                    total+=bytes.length; if(total>512L*1024*1024) throw new IOException("Expanded size limit exceeded");
                    if(entries.putIfAbsent(entry.getName(),bytes)!=null) throw new IOException("Duplicate entry");
                }
            }
        }
        Map<String,byte[]> current=entries; var reports=new ArrayList<String>();
        for(String name:jarPasses) {
            if(name.equals(dev.classrevive.transforms.skid.SkidfuscatorReverser.ID)) {
                var result=new dev.classrevive.transforms.skid.SkidfuscatorReverser().apply(current,budget-changes.size());
                current=result.entries(); changes.addAll(result.changes().subList(0,Math.min(result.changes().size(),budget-changes.size())));
                reports.add(result.report().toString());
            } else if(name.equals(dev.classrevive.transforms.skid.AidsfuscatorReverser.ID)) {
                var result=new dev.classrevive.transforms.skid.AidsfuscatorReverser().apply(current,budget-changes.size());
                current=result.entries(); changes.addAll(result.changes().subList(0,Math.min(result.changes().size(),budget-changes.size())));
                reports.add(result.report().toString());
            }
        }
        // Per-class passes plan over the classes as the whole-jar passes left them.
        var classes=new ArrayList<byte[]>();
        current.forEach((name,bytes)->{ if(name.endsWith(".class")) classes.add(bytes); });
        var classPasses=BuiltinPasses.select(args[3],classes);
        var pass=classPasses.isEmpty()?null:new PassPipeline(classPasses);
        try(var zip=new ZipOutputStream(Files.newOutputStream(output.resolve("transformed.jar"),StandardOpenOption.CREATE_NEW))) {
            for(var entry:current.entrySet()) {
                byte[] bytes=entry.getValue();
                if(pass!=null && entry.getKey().endsWith(".class")) bytes=transform(bytes,pass,changes,budget);
                write(zip,entry.getKey(),bytes);
            }
        }
        if(!reports.isEmpty()) Files.writeString(output.resolve("jar-passes.txt"),String.join(System.lineSeparator(),reports),StandardOpenOption.CREATE_NEW);
        // Binary strings are length-delimited; target-controlled names cannot inject audit rows.
        try(var audit=new DataOutputStream(Files.newOutputStream(output.resolve("audit.bin"),StandardOpenOption.CREATE_NEW))) {
            audit.writeInt(1); audit.writeInt(changes.size()); audit.writeBoolean(changes.size()==budget);
            for(var change:changes) {
                audit.writeUTF(change.pass()); audit.writeUTF(change.className()); audit.writeUTF(change.method()); audit.writeUTF(change.descriptor());
                audit.writeInt(change.instructionIndex()); audit.writeInt(change.opcode()); audit.writeInt(change.left()); audit.writeInt(change.right()); audit.writeInt(change.result());
            }
        }
    }
    private static byte[] transform(byte[] bytes,PassPipeline pass,List<TransformResult.Change> changes,int budget) {
        if(changes.size()==budget) return bytes;
        var result=pass.apply(bytes,budget-changes.size()); changes.addAll(result.changes()); return result.bytes();
    }
    private static byte[] readBounded(InputStream stream,int limit) throws IOException {
        try(stream) { byte[] bytes=stream.readNBytes(limit+1); if(bytes.length>limit) throw new IOException("Entry size limit exceeded"); return bytes; }
    }
    private static void write(ZipOutputStream zip,String name,byte[] bytes) throws IOException {
        var entry=new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
}
