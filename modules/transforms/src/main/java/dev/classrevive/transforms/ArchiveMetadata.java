package dev.classrevive.transforms;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/** Deterministic rewrite of standard manifest class attributes and META-INF/services entries. */
final class ArchiveMetadata {
    private ArchiveMetadata() {}
    private static final String SERVICES = "META-INF/services/";
    private static final List<String> CLASS_ATTRIBUTES = List.of("Main-Class","Premain-Class","Agent-Class","Launcher-Agent-Class");
    private static final Pattern PROVIDER = Pattern.compile("^(\\s*)([^\\s#]+)(.*)$");
    record Service(String name, byte[] bytes) {}

    static byte[] rewriteManifest(byte[] bytes, Map<String,String> forward) throws IOException {
        Manifest manifest;
        try (var in = new ByteArrayInputStream(bytes)) { manifest = new Manifest(in); }
        boolean changed = false;
        for (String attribute : CLASS_ATTRIBUTES) {
            var name = new Attributes.Name(attribute);
            String value = manifest.getMainAttributes().getValue(name);
            if (value == null) continue;
            String mapped = forward.get(value.replace('.','/'));
            if (mapped != null) { manifest.getMainAttributes().put(name, mapped.replace('/','.')); changed = true; }
        }
        if (!changed) return bytes;
        var out = new ByteArrayOutputStream(); manifest.write(out); return out.toByteArray();
    }

    static Service rewriteServices(String path, byte[] bytes, Map<String,String> forward) throws IOException {
        String dotted = path.substring(SERVICES.length());
        String mappedInterface = forwardDotted(dotted, forward);
        String newPath = mappedInterface == null ? path : SERVICES + mappedInterface;
        String text = decodeStrict(bytes,"Service file").replace("\r\n","\n").replace('\r','\n');
        var lines = text.split("\n",-1);
        var rebuilt = new StringBuilder(); boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            var matcher = PROVIDER.matcher(line);
            if (matcher.matches()) {
                String mapped = forwardDotted(matcher.group(2), forward);
                if (mapped != null) { line = matcher.group(1) + mapped + matcher.group(3); changed = true; }
            }
            if (i > 0) rebuilt.append('\n');
            rebuilt.append(line);
        }
        if (!changed) return new Service(newPath, bytes);
        return new Service(newPath, rebuilt.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String forwardDotted(String dotted, Map<String,String> forward) {
        String mapped = forward.get(dotted.replace('.','/'));
        return mapped == null ? null : mapped.replace('/','.');
    }

    static String decodeStrict(byte[] bytes,String what) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException(what + " is not valid UTF-8");
        }
    }
}
