package dev.classrevive.sandbox;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Read-only daemon check; availability alone does not verify containment. */
public final class SandboxProbe {
    public record Result(boolean available,String status,String detail) {}
    public Result check(String docker,Duration timeout) throws InterruptedException {
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Probe timeout must be >0 and <=30 seconds");
        Process process=null; Thread reader=null; var output=new ByteArrayOutputStream();
        try {
            var builder=new ProcessBuilder(docker,"--host",SandboxPolicy.localEndpoint(),"info","--format","{{.OSType}}").redirectErrorStream(true);
            builder.environment().keySet().removeIf(key->key.startsWith("DOCKER_") || key.equals("SSH_AUTH_SOCK"));
            process=builder.start(); process.getOutputStream().close(); Process running=process;
            reader=Thread.ofVirtual().start(()->{
                try(var stream=running.getInputStream()) { byte[] buffer=new byte[1024]; int count;
                    while((count=stream.read(buffer))!=-1) synchronized(output) { if(output.size()<8192) output.write(buffer,0,Math.min(count,8192-output.size())); }
                } catch(IOException ignored) { }
            });
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)) return new Result(false,"UNAVAILABLE","Docker probe timed out");
            reader.join(1000);
            String text; synchronized(output) { text=output.toString(java.nio.charset.StandardCharsets.UTF_8).trim(); }
            if(process.exitValue()!=0) return new Result(false,"UNAVAILABLE","Local Docker daemon did not answer successfully");
            if(!text.equals("linux")) return new Result(false,"UNSUPPORTED","A Linux Docker daemon is required");
            return new Result(true,"AVAILABLE_NOT_VERIFIED","Daemon available; image and isolation conformance are not verified. Target execution remains disabled.");
        } catch(IOException error) { return new Result(false,"UNAVAILABLE","Docker CLI unavailable: "+error.getClass().getSimpleName()); }
        finally {
            if(process!=null) {
                if(process.isAlive()) { process.descendants().forEach(child->child.destroyForcibly()); process.destroyForcibly(); }
                try { process.getInputStream().close(); } catch(IOException ignored) { }
            }
            if(reader!=null) reader.interrupt();
        }
    }
}
