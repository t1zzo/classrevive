package dev.classrevive.cli;

import dev.classrevive.pipeline.DiagnosticJob;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class DiagnoseCommand {
    static int run(String[] args,PrintStream out,PrintStream err) {
        try {
            var nativeOptions=new NativeOptions[1];
            args=NativeOptions.strip(args,nativeOptions);
            // Phase 2CV: without --output, a new <name>-diagnosis folder in the current folder.
            if(args.length>=2 && !Arrays.asList(args).contains("--output")) args=Outputs.withDefault(args,"diagnosis");
            if(args.length<4 || args.length%2!=0) throw new IllegalArgumentException("Expected input and --output <new-directory>");
            var options=new HashMap<String,String>();
            for(int i=2;i<args.length;i+=2) {
                if(!Set.of("--output","--timeout-seconds","--entry").contains(args[i]) || options.putIfAbsent(args[i],args[i+1])!=null)
                    throw new IllegalArgumentException("Unknown or duplicate option: "+args[i]);
            }
            if(!options.containsKey("--output")) throw new IllegalArgumentException("Missing --output");
            Duration timeout=dev.classrevive.workbench.RequestLimits.timeoutSeconds(Integer.parseInt(options.getOrDefault("--timeout-seconds","60")));
            Path output=Path.of(options.get("--output"));
            if(IdentifyCommand.needsIdentification(Path.of(args[1]))) out.println("Input is not named .class or .jar: identifying it from its bytes (nothing is run).");
            // Phase 2DB: --entry picks the nested jar of a bundle of jars.
            var result=new DiagnosticJob().run(Path.of(args[1]),output,timeout,options.get("--entry"));
            out.printf("Classes scanned: %d; findings: %d; limit reached: %s%n",result.classes(),result.findings(),result.limited());
            out.printf("Static signals: class-loading=%d; crypto=%d%n",result.signalCounts().getOrDefault("CLASS_LOADING_API",0),
                result.signalCounts().getOrDefault("CRYPTO_API",0));
            if(!result.obfuscation().isEmpty()) for(String line:result.obfuscation().split("\n")) out.println(Main.safe(line));
            Path absolute=output.toAbsolutePath().normalize();
            String origin=dev.classrevive.pipeline.InputIdentifier.attach(absolute,absolute.resolve("obfuscation.txt"),absolute.resolve("diagnostics.json"));
            if(origin!=null) out.println(Main.safe(origin));
            nativeOptions[0].run(absolute,absolute.resolve("obfuscation.txt"),absolute.resolve("diagnostics.json"),out);
            out.println("Evidence: "+Main.safe(output.toAbsolutePath().resolve("diagnostics.json").toString()));
            out.println("Static API references are clues only; they do not prove runtime reachability, packing, encryption or malicious behavior.");
            return result.successful()&&!result.limited()?0:1;
        } catch(IllegalArgumentException error) { err.println("Invalid arguments: "+Main.safe(String.valueOf(error.getMessage()))); return 2; }
        catch(dev.classrevive.pipeline.InputIdentifier.NoJavaException error) { return IdentifyCommand.noJava(error,out,err); }
        catch(IOException error) { err.println("Diagnostics failed: "+Main.safe(String.valueOf(error.getMessage()))); return 1; }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); err.println("Diagnostics interrupted."); return 130; }
    }
}
