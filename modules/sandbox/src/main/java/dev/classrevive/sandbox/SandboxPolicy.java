package dev.classrevive.sandbox;

import java.nio.file.*;
import java.util.*;

/** Experimental policy builder only. Runtime conformance must pass before target execution is enabled. */
public final class SandboxPolicy {
    private SandboxPolicy() {}
    public static String localEndpoint() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "npipe:////./pipe/dockerDesktopLinuxEngine" : "unix:///var/run/docker.sock";
    }
    static void validateEndpoint(String endpoint) {
        if(!Set.of("npipe:////./pipe/dockerDesktopLinuxEngine","unix:///var/run/docker.sock").contains(endpoint))
            throw new IllegalArgumentException("Only explicit local Docker endpoints are supported");
    }
    public static List<String> command(String docker,String endpoint,String image,String name,Path input,String main,List<String> arguments) {
        validateEndpoint(endpoint); arguments=List.copyOf(arguments);
        if(!image.matches("sha256:[a-f0-9]{64}") || !name.matches("classrevive-[a-z0-9-]{1,64}"))
            throw new IllegalArgumentException("Require immutable image ID and ClassRevive container name");
        if(!main.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") || arguments.size()>64)
            throw new IllegalArgumentException("Invalid main class or argument count");
        String path=input.toAbsolutePath().normalize().toString();
        if(path.contains(",") || path.contains("\n") || path.contains("\r") || path.contains("\""))
            throw new IllegalArgumentException("Ambiguous mount path");
        int chars=0;
        for(String argument:arguments) { chars+=argument.length(); if(argument.indexOf('\0')>=0 || chars>8192) throw new IllegalArgumentException("Argument budget exceeded"); }
        var command=new ArrayList<>(List.of(docker,"--host",endpoint,"run","--pull=never","--rm","--name",name,
            "--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--user=65534:65534",
            "--memory=512m","--memory-swap=512m","--cpus=1","--pids-limit=64","--ipc=private","--cgroupns=private",
            "--no-healthcheck","--log-driver=none","--ulimit=nofile=256:256",
            "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777",
            "--mount","type=bind,source="+path+",destination=/input,readonly","--workdir=/tmp",
            "--env=JAVA_TOOL_OPTIONS=","--env=_JAVA_OPTIONS=","--env=JDK_JAVA_OPTIONS=","--env=CLASSPATH=",
            "--entrypoint=/opt/java/openjdk/bin/java",image,"-Xmx256m","-XX:MaxMetaspaceSize=128m","-XX:ActiveProcessorCount=1",
            "-Djava.io.tmpdir=/tmp","-Dfile.encoding=UTF-8","-cp","/input/*",main));
        command.addAll(arguments); return List.copyOf(command);
    }
}
