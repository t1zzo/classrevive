package dev.classrevive.bytecode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reads a parent-validated snapshot; emits bounded data without extracting or loading target classes. */
public final class DynamicWorker {
    record Row(String entry,DynamicScanner.Finding finding) {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected input and output");
        var rows=new ArrayList<Row>(); int classes=0; boolean limited=false; long expanded=0;
        Path input=Path.of(args[0]);
        // Phase 2BZ: the same pass builds the static obfuscation profile (resources by name and size only, never read).
        var profile=new ObfuscationProfile.Builder();
        if(input.toString().endsWith(".class")) {
            byte[] bytes=read(Files.newInputStream(input));
            var result=new DynamicScanner().scan(bytes,10000);
            for(var finding:result.findings()) rows.add(new Row("input.class",finding));
            classes=1; limited=result.limitReached(); profile.addClass(bytes);
        } else try(var zip=new ZipFile(input.toFile())) {
            var entries=zip.entries(); int visited=0;
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement(); if(++visited>20000) throw new IOException("Entry limit");
                if(!entry.getName().toLowerCase(Locale.ROOT).endsWith(".class")) { if(!entry.isDirectory()) profile.addResource(entry.getName(),entry.getSize()); continue; }
                byte[] bytes=read(zip.getInputStream(entry)); expanded+=bytes.length;
                if(expanded>512L*1024*1024) throw new IOException("Expanded byte limit");
                profile.addClass(bytes);
                if(rows.size()==10000) { limited=true; continue; }
                var result=new DynamicScanner().scan(bytes,10000-rows.size());
                for(var finding:result.findings()) rows.add(new Row(entry.getName(),finding));
                classes++; limited|=result.limitReached();
            }
        }
        writeAudit(Path.of(args[1]).resolve("dynamic.bin"),classes,limited,rows,16*1024*1024);
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]).resolve("obfuscation.bin"),StandardOpenOption.CREATE_NEW)))) {
            profile.build().write(out);
        }
    }
    static void writeAudit(Path path,int classes,boolean limited,List<Row> rows,int budget) throws IOException {
        if(budget<13) throw new IllegalArgumentException("Audit budget too small");
        var encoded=new ArrayList<byte[]>(); int size=13;
        for(var row:rows) {
            var buffer=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(buffer)) {
                var f=row.finding(); out.writeUTF(row.entry()); out.writeUTF(f.className()); out.writeUTF(f.method()); out.writeUTF(f.descriptor());
                out.writeInt(f.instruction()); out.writeUTF(f.kind()); out.writeUTF(f.owner()); out.writeUTF(f.name()); out.writeUTF(f.targetDescriptor()); out.writeInt(f.bootstrapArguments());
            }
            if(buffer.size()>budget-size) { limited=true; break; }
            size+=buffer.size(); encoded.add(buffer.toByteArray());
        }
        try(var out=new DataOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))) {
            out.writeInt(1); out.writeInt(classes); out.writeBoolean(limited); out.writeInt(encoded.size());
            for(byte[] row:encoded) out.write(row);
        }
    }
    private static byte[] read(InputStream stream) throws IOException {
        try(stream) { byte[] bytes=stream.readNBytes(16*1024*1024+1); if(bytes.length>16*1024*1024) throw new IOException("Class byte limit"); return bytes; }
    }
}
