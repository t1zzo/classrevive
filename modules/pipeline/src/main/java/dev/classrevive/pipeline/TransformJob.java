package dev.classrevive.pipeline;

import dev.classrevive.intake.InventoryReader;
import dev.classrevive.model.*;
import dev.classrevive.engine.*;
import dev.classrevive.transforms.TransformEngine;
import dev.classrevive.transforms.BuiltinPasses;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import java.time.Duration;
import java.util.*;

public final class TransformJob {
    /**
     * {@code methods}: each changed method as {@code class.name+descriptor} (Phase 2CM).
     * {@code link}: Phase 2CN, the JVM verifier's verdict on the transformed classes (null when not checked).
     */
    public record Result(boolean successful,int changes,boolean limitReached,EngineResult worker,SortedSet<String> methods,
                         dev.classrevive.bytecode.LinkCheck.Result link) {
        public Result { methods=Collections.unmodifiableSortedSet(new TreeSet<>(methods)); }
        public Result(boolean successful,int changes,boolean limitReached,EngineResult worker,SortedSet<String> methods) {
            this(successful,changes,limitReached,worker,methods,null);
        }
    }
    public Result run(Path input,Path output,int budget,Duration timeout) throws IOException,InterruptedException {
        return run(input,output,budget,timeout,BuiltinPasses.DEFAULT);
    }
    public Result run(Path input,Path output,int budget,Duration timeout,String passes) throws IOException,InterruptedException {
        return run(input,output,budget,timeout,passes,List.of());
    }
    /** @param dependencies Phase 2DD: JARs the JVM verifier may load (never link) while it checks the transformed classes */
    public Result run(Path input,Path output,int budget,Duration timeout,String passes,List<Path> dependencies) throws IOException,InterruptedException {
        dependencies=List.copyOf(dependencies);
        var selected = BuiltinPasses.names(passes);
        if(budget<1 || budget>10000) throw new IllegalArgumentException("Change budget must be 1..10000");
        if(timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("Invalid timeout");
        input=input.toAbsolutePath().normalize(); output=SafePaths.resolveParents(output);
        String name=input.getFileName()==null?"":input.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean jar=name.endsWith(".jar");
        if((!jar&&!name.endsWith(".class")) || !Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular .jar or .class input");
        if(output.getParent()==null || !Files.isDirectory(output.getParent())) throw new IOException("Output parent must exist");
        Files.createDirectory(output);
        var report=new TreeMap<String,Object>(); report.put("schema_version",1); report.put("kind","static_transform");
        report.put("behavior","NOT_VERIFIED");
        report.put("passes",selected.stream().map(pass -> BuiltinPasses.JAR_PASSES.contains(pass) ? pass : BuiltinPasses.select(pass).getFirst().id()).toList());
        report.put("instruction_index_scope","input of the named pass within its class");
        try {
            Path snapshot=output.resolve(jar?"input.jar":"input.class");
            try(var in=Files.newInputStream(input);var out=Files.newOutputStream(snapshot,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[8192]; long total=0; int count;
                while((count=in.read(buffer))!=-1) { total+=count; if(total>Limits.defaults().maxInputBytes()) throw new IOException("Input limit exceeded"); out.write(buffer,0,count); }
            }
            var inventory=new InventoryReader().inspect(snapshot,Limits.defaults());
            report.put("input_sha256",inventory.sha256());
            if(!inventory.successful()) throw new IOException("Input rejected: "+inventory.diagnostics().getFirst().code());
            // Phase 2BV: decompile disambiguates case-only collisions and drops oversized resources; this command would write them back.
            if(!inventory.notices().isEmpty()) throw new IOException("Input rejected: archive entries that collide by letter case or exceed the entry limit are supported by decompile only");
            if(inventory.entries().stream().noneMatch(e -> e.classInfo()!=null)) throw new IOException("Input has no classes");
            if(jar) ArchiveGuards.rejectSignedOrDigested(snapshot);
            else {
                String className=inventory.entries().getFirst().classInfo().name();
                if(className.isEmpty() || className.endsWith("/") || !InventoryReader.isSafeEntryName(className+".class")) throw new IOException("Unsafe standalone class identity");
            }
            var worker=new ProcessRunner().runTransform(new TransformEngine(budget,passes),snapshot,output.resolve("worker"),timeout);
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            report.put("input_sha256",inventory.sha256()); report.put("worker",EvidenceReport.engine(worker)); report.put("behavior","NOT_VERIFIED");
            boolean success=worker.status()==EngineResult.Status.OUTPUT_PRODUCED;
            var changes=new ArrayList<Map<String,Object>>(); boolean limit=false; var methods=new TreeSet<String>();
            dev.classrevive.bytecode.LinkCheck.Result link=null;
            if(success) {
                Path artifact=output.resolve("worker/transformed/transformed.jar");
                var transformed=new InventoryReader().inspect(artifact,Limits.defaults());
                if(!transformed.successful()) throw new IOException("Transformed output inventory failed");
                Path auditPath=output.resolve("worker/transformed/audit.bin");
                if(Files.size(auditPath)>16L*1024*1024) throw new IOException("Audit exceeds limit");
                try(var audit=new DataInputStream(Files.newInputStream(auditPath))) {
                    if(audit.readInt()!=1) throw new IOException("Unknown audit version");
                    int count=audit.readInt(); limit=audit.readBoolean();
                    if(count<0||count>budget) throw new IOException("Invalid audit count");
                    for(int i=0;i<count;i++) changes.add(Map.of("pass",audit.readUTF(),"class",audit.readUTF(),"method",audit.readUTF(),"descriptor",audit.readUTF(),
                        "instruction_index",audit.readInt(),"opcode",audit.readInt(),"left",audit.readInt(),"right",audit.readInt(),"result",audit.readInt()));
                    if(audit.read()!=-1) throw new IOException("Trailing audit data");
                    for(var change:changes) methods.add(change.get("class")+"."+change.get("method")+change.get("descriptor"));
                }
                report.put("output_sha256",transformed.sha256()); report.put("artifact","worker/transformed/transformed.jar");
                // Phase 2CN: whole-jar passes rewrite control flow, so the JVM verifier checks every transformed class (in a
                // fresh JVM; classes are linked, never initialized). Missing dependencies show up as link errors.
                if(!BuiltinPasses.jarPasses(passes).isEmpty()) {
                    link=new dev.classrevive.bytecode.LinkCheck().check(artifact,output.resolve("link"),dependencies,timeout);
                    var linkData=new TreeMap<String,Object>(); linkData.put("linked",link.linked()); linkData.put("classes",link.classes());
                    if(link.errorType()!=null) linkData.put("error_type",link.errorType());
                    report.put("link_check",linkData);
                    Path passReport=output.resolve("worker/transformed/jar-passes.txt");
                    if(Files.isRegularFile(passReport,LinkOption.NOFOLLOW_LINKS) && Files.size(passReport)<64*1024) report.put("jar_passes",Files.readString(passReport));
                }
            }
            report.put("status",success?"OUTPUT_VALIDATED":"WORKER_FAILED"); report.put("changes",changes); report.put("change_limit_reached",limit);
            report.put("limitation","Static inventory validation only; no execution or behavioral equivalence proof. Unknown attributes are preserved unchanged.");
            EvidenceReport.write(output.resolve("transform.json"),report);
            return new Result(success,changes.size(),limit,worker,methods,link);
        } catch(IOException error) {
            report.put("status","FAILED"); report.put("error",String.valueOf(error.getMessage()));
            try { EvidenceReport.write(output.resolve("transform.json"),report); }
            catch(IOException reporting) { error.addSuppressed(reporting); }
            try { Files.writeString(output.resolve("failure.txt"),String.valueOf(error.getMessage()),StandardOpenOption.CREATE_NEW); }
            catch(IOException reporting) { error.addSuppressed(reporting); }
            throw error;
        }
    }
}
