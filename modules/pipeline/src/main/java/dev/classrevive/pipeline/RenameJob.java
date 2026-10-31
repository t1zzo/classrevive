package dev.classrevive.pipeline;

import dev.classrevive.engine.*;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.intake.MultiRelease;
import dev.classrevive.model.ClassInfo;
import dev.classrevive.model.Limits;
import dev.classrevive.transforms.ClassRenamer;
import dev.classrevive.transforms.MappingFile;
import dev.classrevive.transforms.ProguardMapping;
import dev.classrevive.transforms.RenameEngine;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Duration;
import java.util.*;

public final class RenameJob {
    public record Result(boolean successful,int classes,int renamed,int members,int reflectionRewrites,EngineResult worker) {}
    public Result run(Path input,Path output,Duration timeout) throws IOException,InterruptedException { return run(input,output,timeout,null); }
    public Result run(Path input,Path output,Duration timeout,Path mappingFile) throws IOException,InterruptedException { return run(input,output,timeout,mappingFile,false); }
    /** With {@code proguard}, the mapping is a ProGuard/R8 mapping.txt whose safely applicable subset restores original names. */
    public Result run(Path input,Path output,Duration timeout,Path mappingFile,boolean proguard) throws IOException,InterruptedException {
        if(proguard&&mappingFile==null) throw new IllegalArgumentException("ProGuard mode requires a mapping file");
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("Timeout must be >0 and <=1 hour");
        input=input.toAbsolutePath().normalize(); output=SafePaths.resolveParents(output);
        String name=input.getFileName()==null?"":input.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean jar=name.endsWith(".jar");
        if((!jar&&!name.endsWith(".class"))||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular .jar or .class input");
        byte[] mappingBytes=null,proguardBytes=null; MappingFile.Plan mappingPlan=null; ProguardMapping.Parsed proguardMapping=null;
        if(mappingFile!=null) {
            mappingFile=mappingFile.toAbsolutePath().normalize();
            if(!Files.isRegularFile(mappingFile,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Mapping file must be a regular file");
            if(proguard) {
                proguardBytes=readBounded(mappingFile,ProguardMapping.MAX_BYTES,"ProGuard mapping");
                proguardMapping=ProguardMapping.parse(proguardBytes);
            } else {
                mappingBytes=readBounded(mappingFile,MappingFile.MAX_BYTES,"Mapping file");
                mappingPlan=MappingFile.parse(mappingBytes);
            }
        }
        if(output.getParent()==null||!Files.isDirectory(output.getParent())) throw new IOException("Output parent must exist");
        Files.createDirectory(output);
        var report=new TreeMap<String,Object>(); report.put("schema_version",1); report.put("kind","class_rename");
        report.put("behavior","NOT_VERIFIED");
        report.put("limitation","Generated aliases or explicit mappings, not recovered originals. Standard manifest class attributes and META-INF/services are rewritten and explicit private-member renames are applied; reflection through variables, non-literal strings, serialization, annotations, other resources, external callers and nested archives are not rewritten.");
        try {
            Path snapshot=output.resolve(jar?"input.jar":"input.class");
            copyBounded(input,snapshot,Limits.defaults().maxInputBytes());
            if(mappingBytes!=null) Files.write(output.resolve("mapping.txt"),mappingBytes,StandardOpenOption.CREATE_NEW);
            var inventory=new InventoryReader().inspect(snapshot,Limits.defaults());
            report.put("input_sha256",inventory.sha256());
            if(!inventory.successful()) throw new IOException("Input rejected: "+inventory.diagnostics().getFirst().code());
            // Phase 2BV: decompile disambiguates case-only collisions and drops oversized resources; this command would write them back.
            if(!inventory.notices().isEmpty()) throw new IOException("Input rejected: archive entries that collide by letter case or exceed the entry limit are supported by decompile only");
            var classEntries=inventory.entries().stream().filter(e->e.classInfo()!=null).toList();
            if(classEntries.isEmpty()) throw new IOException("Input has no classes");
            if(jar) {
                // Unlike decompile, rename cannot skip variants: base-only renaming would leave META-INF/versions classes and
                // module descriptors referring to the old names, so the JVM would load inconsistent code on Java 9+.
                long versioned=inventory.entries().stream().filter(e->MultiRelease.versioned(e.name())).count();
                if(versioned>0)
                    throw new IOException("Multi-release archives are unsupported by rename ("+versioned
                        +" META-INF/versions entries); renaming only the base classes would leave the versioned variants inconsistent");
                for(var entry:classEntries) {
                    String internal=entry.classInfo().name();
                    if(!entry.name().equals(internal+".class")) throw new IOException("Class entry path does not match its internal name");
                    rejectDescriptor(internal);
                }
            } else {
                String internal=classEntries.getFirst().classInfo().name();
                if(internal.isEmpty()||internal.endsWith("/")||!InventoryReader.isSafeEntryName(internal+".class"))
                    throw new IOException("Unsafe standalone class identity");
                rejectDescriptor(internal);
            }
            if(jar) ArchiveGuards.rejectSignedOrDigested(snapshot);
            if(proguardMapping!=null) {
                Files.write(output.resolve("proguard-mapping.txt"),proguardBytes,StandardOpenOption.CREATE_NEW);
                var shapes=new TreeMap<String,ProguardMapping.ClassShape>();
                for(var entry:classEntries) { var info=entry.classInfo();
                    shapes.put(info.name(),new ProguardMapping.ClassShape(info.access(),
                        info.fields().stream().map(m->new ProguardMapping.Member(m.name(),m.descriptor(),m.access())).toList(),
                        info.methods().stream().map(m->new ProguardMapping.Member(m.name(),m.descriptor(),m.access())).toList())); }
                var conversion=ProguardMapping.convert(proguardMapping,shapes);
                var skipped=new TreeMap<String,Object>(); conversion.skipped().forEach((reason,count)->skipped.put(reason.name(),count));
                var samples=new ArrayList<Object>();
                for(var skip:conversion.samples()) samples.add(new TreeMap<String,Object>(Map.of("reason",skip.reason().name(),"rule",skip.rule())));
                var summary=new TreeMap<String,Object>();
                summary.put("applicable_classes",conversion.plan().classes().size()); summary.put("applicable_members",conversion.plan().members().size());
                summary.put("inline_frames_ignored",conversion.inlineFrames()); summary.put("skipped",skipped); summary.put("skipped_samples",samples);
                summary.put("note","Names come from the supplied mapping file. Cross-package classes and non-private members need hierarchy-aware renaming and are skipped.");
                report.put("proguard",summary);
                if(conversion.applied()==0) throw new IOException("ProGuard mapping has no rule applicable to this input");
                mappingBytes=ProguardMapping.format(conversion.plan()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if(mappingBytes.length>MappingFile.MAX_BYTES) throw new IOException("Converted mapping exceeds 1 MiB");
                mappingPlan=MappingFile.parse(mappingBytes);
                Files.write(output.resolve("mapping.txt"),mappingBytes,StandardOpenOption.CREATE_NEW);
            }
            var engine=mappingBytes==null?new RenameEngine():new RenameEngine(output.resolve("mapping.txt"));
            var worker=new ProcessRunner().runRename(engine,snapshot,output.resolve("worker"),timeout);
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            report.put("worker",EvidenceReport.engine(worker));
            boolean success=worker.status()==EngineResult.Status.OUTPUT_PRODUCED;
            var forward=new TreeMap<String,String>(); var members=new TreeMap<ClassRenamer.MemberKey,String>(); int reflectionRewrites=0;
            if(success) {
                Path artifact=output.resolve("worker/renamed/renamed.jar");
                Path evidence=output.resolve("worker/renamed/map.bin");
                if(Files.size(evidence)>16L*1024*1024) throw new IOException("Mapping evidence exceeds limit");
                try(var in=new DataInputStream(Files.newInputStream(evidence))) {
                    if(in.readInt()!=3) throw new IOException("Unknown mapping schema");
                    int count=in.readInt(); if(count<0||count>20000) throw new IOException("Invalid mapping count");
                    for(int i=0;i<count;i++) {
                        String from=in.readUTF(),to=in.readUTF();
                        if(forward.put(from,to)!=null) throw new IOException("Duplicate mapping key");
                    }
                    int memberCount=in.readInt(); if(memberCount<0||memberCount>50000) throw new IOException("Invalid member mapping count");
                    for(int i=0;i<memberCount;i++) {
                        var key=new ClassRenamer.MemberKey(in.readUTF(),in.readUTF(),in.readUTF(),in.readBoolean());
                        if(members.put(key,in.readUTF())!=null) throw new IOException("Duplicate member mapping key");
                    }
                    reflectionRewrites=in.readInt(); if(reflectionRewrites<0||reflectionRewrites>1000000) throw new IOException("Invalid reflection rewrite count");
                    if(in.read()!=-1) throw new IOException("Trailing mapping evidence");
                }
                var names=new TreeSet<String>();
                for(var entry:classEntries) names.add(entry.classInfo().name());
                if(!names.containsAll(forward.keySet())) throw new IOException("Mapping references unknown classes");
                if(new HashSet<>(forward.values()).size()!=forward.size()) throw new IOException("Duplicate mapping target");
                var classInfoByName=new TreeMap<String,ClassInfo>();
                for(var entry:classEntries) classInfoByName.put(entry.classInfo().name(),entry.classInfo());
                for(var entry:members.entrySet()) {
                    var key=entry.getKey(); var info=classInfoByName.get(key.owner());
                    if(info==null) throw new IOException("Member mapping references unknown classes");
                    var memberList=key.field()?info.fields():info.methods();
                    boolean found=false;
                    for(var member:memberList) if(member.name().equals(key.name())&&member.descriptor().equals(key.descriptor())) {
                        if((member.access()&2)==0) throw new IOException("Member mapping targets a non-private member");
                        found=true; break;
                    }
                    if(!found) throw new IOException("Member mapping references unknown members");
                    if(key.field()&&(info.access()&0x10000)!=0) throw new IOException("Record member mapping is unsupported");
                }
                var memberTargets=new HashSet<String>();
                for(var entry:members.entrySet()) { var key=entry.getKey();
                    if(!memberTargets.add(key.owner()+"\u0000"+key.field()+"\u0000"+key.descriptor()+"\u0000"+entry.getValue()))
                        throw new IOException("Duplicate member mapping target");
                }
                var renamedInventory=new InventoryReader().inspect(artifact,Limits.defaults());
                if(!renamedInventory.successful()) throw new IOException("Renamed output inventory failed");
                var expected=new TreeSet<String>(); for(String className:names) expected.add(forward.getOrDefault(className,className));
                var actual=new TreeSet<String>(); for(var entry:renamedInventory.entries()) if(entry.classInfo()!=null) actual.add(entry.classInfo().name());
                if(!expected.equals(actual)) throw new IOException("Renamed class set mismatch");
                report.put("output_sha256",copyAndHash(artifact,output.resolve("renamed.jar")));
                report.put("artifact","renamed.jar");
            }
            var inverse=new TreeMap<String,String>(); forward.forEach((from,to)->inverse.put(to,from));
            report.put("status",success?"RENAMED":"WORKER_FAILED"); report.put("classes",classEntries.size());
            report.put("mapping",proguardMapping!=null?"proguard":mappingPlan==null?"planned":"explicit");
            if(mappingPlan!=null) report.put("member_rules",mappingPlan.members().size());
            report.put("renamed",forward.size()); report.put("members_renamed",members.size()); report.put("reflection_rewrites",reflectionRewrites);
            report.put("forward",forward); report.put("inverse",inverse);
            EvidenceReport.write(output.resolve("rename.json"),report);
            return new Result(success,classEntries.size(),forward.size(),members.size(),reflectionRewrites,worker);
        } catch(IOException error) {
            report.put("status","FAILED"); report.put("error",String.valueOf(error.getMessage()));
            try { EvidenceReport.write(output.resolve("rename.json"),report); }
            catch(IOException reporting) { error.addSuppressed(reporting); }
            try { Files.writeString(output.resolve("failure.txt"),String.valueOf(error.getMessage()),StandardOpenOption.CREATE_NEW); }
            catch(IOException reporting) { error.addSuppressed(reporting); }
            throw error;
        }
    }
    private static void rejectDescriptor(String internal) throws IOException {
        if(internal.equals("module-info")||internal.equals("package-info")||internal.endsWith("/package-info"))
            throw new IOException("Module and package descriptors are unsupported");
    }
    private static byte[] readBounded(Path file,long limit,String what) throws IOException {
        try(var in=Files.newInputStream(file)) {
            byte[] bytes=in.readNBytes((int)limit+1);
            if(bytes.length>limit) throw new IOException(what+" exceeds limit");
            return bytes;
        }
    }
    private static void copyBounded(Path input,Path target,long limit) throws IOException {
        try(var in=Files.newInputStream(input);var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
            byte[] buffer=new byte[8192]; long total=0; int count;
            while((count=in.read(buffer))!=-1) { total+=count; if(total>limit) throw new IOException("Input limit exceeded"); out.write(buffer,0,count); }
        }
    }
    private static String copyAndHash(Path source,Path target) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var in=new DigestInputStream(Files.newInputStream(source),digest);var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                in.transferTo(out);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
