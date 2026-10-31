package dev.classrevive.pipeline;

import dev.classrevive.bytecode.DynamicWorker;
import dev.classrevive.engine.*;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.model.Limits;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;

public final class DiagnosticJob {
    /** {@code obfuscation}: Phase 2BZ readable obfuscation summary (several lines), empty when the worker failed. */
    public record Result(boolean successful,int classes,int findings,boolean limited,Map<String,Integer> signalCounts,String obfuscation) {
        public Result {
            signalCounts=Collections.unmodifiableMap(new TreeMap<>(signalCounts));
        }
        public Result(boolean successful,int classes,int findings,boolean limited,Map<String,Integer> signalCounts) {
            this(successful,classes,findings,limited,signalCounts,"");
        }
        public Result(boolean successful,int classes,int findings,boolean limited) {
            this(successful,classes,findings,limited,Map.of());
        }
    }
    public Result run(Path input,Path output,Duration timeout) throws IOException,InterruptedException { return run(input,output,timeout,null); }
    /** Phase 2DB: {@code entry} picks the nested jar of a bundle of jars (null: the default rule). */
    public Result run(Path input,Path output,Duration timeout,String entry) throws IOException,InterruptedException {
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("Timeout must be >0 and <=1 hour");
        input=input.toAbsolutePath().normalize(); output=SafePaths.resolveParents(output);
        String name=input.getFileName()==null?"":input.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular .class or .jar input");
        if(entry!=null&&(name.endsWith(".class")||name.endsWith(".jar"))) throw new IllegalArgumentException("--entry applies only to a bundle of jars, not to a .class or .jar input");
        // Phase 2CE: other inputs are identified; Java found in them is taken as data (see DecompilationJob).
        InputIdentifier.Resolved resolved=null;
        if(!name.endsWith(".class")&&!name.endsWith(".jar")) { resolved=InputIdentifier.resolve(input,timeout,entry); name=resolved.jar()?"input.jar":"input.class"; }
        Files.createDirectory(output);
        Path snapshot=output.resolve(name.endsWith(".jar")?"input.jar":"input.class");
        if(resolved!=null) { Files.write(snapshot,resolved.bytes(),StandardOpenOption.CREATE_NEW); InputIdentifier.record(output,resolved); }
        else try(var in=Files.newInputStream(input);var out=Files.newOutputStream(snapshot,StandardOpenOption.CREATE_NEW)) {
            byte[] buffer=new byte[8192]; int count; long total=0;
            while((count=in.read(buffer))!=-1) { total+=count; if(total>Limits.defaults().maxInputBytes()) throw new IOException("Input byte limit"); out.write(buffer,0,count); }
        }
        var inventory=new InventoryReader().inspect(snapshot,Limits.defaults());
        if(!inventory.successful()) throw new IOException("Input inventory rejected");
        var engine=new DecompilerEngine() {
            public String id() { return "dynamic-diagnostics"; } public String version() { return "1"; }
            public List<String> command(Path source,Path destination) {
                return EngineCommand.javaWorker(DynamicWorker.class,DynamicWorker.class.getName(),List.of("org/objectweb/asm/ClassReader.class","org/objectweb/asm/tree/ClassNode.class"),source.toString(),destination.toString());
            }
        };
        var worker=new ProcessRunner().runDiagnostics(engine,snapshot,output.resolve("worker"),timeout);
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var report=new TreeMap<String,Object>(); report.put("schema_version",1); report.put("kind","dynamic_linkage_diagnostics");
        report.put("input_sha256",inventory.sha256()); report.put("worker",EvidenceReport.engine(worker)); report.put("behavior","NOT_VERIFIED");
        report.put("signal_interpretation","CLASS_LOADING_API and CRYPTO_API are references only; they do not prove runtime reachability, packing, encryption or malicious behavior.");
        var findings=new ArrayList<Map<String,Object>>(); int classes=0; boolean limited=false;
        boolean success=worker.status()==EngineResult.Status.OUTPUT_PRODUCED;
        if(success) {
            Path data=output.resolve("worker/diagnostics/dynamic.bin"); if(Files.size(data)>16L*1024*1024) throw new IOException("Diagnostic evidence exceeds limit");
            try(var in=new DataInputStream(Files.newInputStream(data))) {
                if(in.readInt()!=1) throw new IOException("Unknown diagnostic schema"); classes=in.readInt(); limited=in.readBoolean(); int count=in.readInt();
                if(classes<0||classes>20000||count<0||count>10000) throw new IOException("Invalid diagnostic counts");
                for(int i=0;i<count;i++) {
                    var row=new TreeMap<String,Object>(); row.put("entry",in.readUTF()); row.put("class",in.readUTF()); row.put("method",in.readUTF()); row.put("descriptor",in.readUTF());
                    row.put("instruction_ordinal",in.readInt()); row.put("kind",in.readUTF()); row.put("owner",in.readUTF()); row.put("name",in.readUTF()); row.put("target_descriptor",in.readUTF()); row.put("bootstrap_arguments",in.readInt()); findings.add(row);
                }
                if(in.read()!=-1) throw new IOException("Trailing diagnostic evidence");
            }
        }
        // Phase 2BZ: static obfuscation profile from the same worker, classified here (rules only, no class data).
        String obfuscationText="";
        Path profileData=output.resolve("worker/diagnostics/obfuscation.bin");
        if(success&&Files.isRegularFile(profileData,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.size(profileData)>1024L*1024) throw new IOException("Obfuscation profile exceeds limit");
            dev.classrevive.bytecode.ObfuscationProfile profile;
            try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(profileData)))) {
                profile=dev.classrevive.bytecode.ObfuscationProfile.read(in);
                if(in.read()!=-1) throw new IOException("Trailing obfuscation profile data");
            }
            var classified=dev.classrevive.bytecode.ObfuscatorClassifier.classify(profile);
            report.put("obfuscation",ObfuscationSummary.json(profile,classified));
            obfuscationText=ObfuscationSummary.text(profile,classified);
            Files.writeString(output.resolve("obfuscation.txt"),obfuscationText+ObfuscationSummary.INTERPRETATION+"\n",StandardOpenOption.CREATE_NEW);
        }
        var signalCounts=new TreeMap<String,Integer>();
        for(var finding:findings) signalCounts.merge((String)finding.get("kind"),1,Integer::sum);
        report.put("status",success?(limited?"PARTIAL":"SCANNED"):"WORKER_FAILED"); report.put("classes_scanned",classes); report.put("limit_reached",limited);
        report.put("signal_counts",signalCounts); report.put("findings",findings);
        report.put("limitation","Static references only, not execution or malware evidence. API patterns are incomplete. Instruction ordinal is not a byte offset. No dynamic target was resolved.");
        EvidenceReport.write(output.resolve("diagnostics.json"),report);
        return new Result(success,classes,findings.size(),limited,signalCounts,obfuscationText);
    }
}
