package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveMetadataTest {
    @Test void rewritesRecognizedManifestAttributesAndKeepsUnchangedBytesIdentical() throws Exception {
        var manifest=new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"p.ab");
        manifest.getMainAttributes().putValue("Agent-Class","p.cd");
        manifest.getMainAttributes().putValue("Custom-Class","p.ab");
        var buffer=new ByteArrayOutputStream(); manifest.write(buffer); byte[] input=buffer.toByteArray();
        byte[] output=ArchiveMetadata.rewriteManifest(input,Map.of("p/ab","p/Class00001"));
        var parsed=new Manifest(new ByteArrayInputStream(output));
        assertEquals("p.Class00001",parsed.getMainAttributes().getValue("Main-Class"));
        assertEquals("p.cd",parsed.getMainAttributes().getValue("Agent-Class"));
        assertEquals("p.ab",parsed.getMainAttributes().getValue("Custom-Class"));
        assertSame(input,ArchiveMetadata.rewriteManifest(input,Map.of("p/other","p/X")));
    }
    @Test void rewritesServicePathAndProviderLines() throws Exception {
        var forward=Map.of("p/ab","p/Class00001","p/cd","p/Class00002");
        byte[] content="# providers\np.cd # keep\n p.cd\nexternal.Provider\n".getBytes(StandardCharsets.UTF_8);
        var result=ArchiveMetadata.rewriteServices("META-INF/services/p.ab",content,forward);
        assertEquals("META-INF/services/p.Class00001",result.name());
        assertEquals("# providers\np.Class00002 # keep\n p.Class00002\nexternal.Provider\n",new String(result.bytes(),StandardCharsets.UTF_8));
        byte[] untouched="p.external\r\n".getBytes(StandardCharsets.UTF_8);
        var same=ArchiveMetadata.rewriteServices("META-INF/services/p.external",untouched,forward);
        assertEquals("META-INF/services/p.external",same.name());
        assertSame(untouched,same.bytes());
    }
    @Test void rejectsInvalidServiceEncoding() {
        assertThrows(IOException.class,()->ArchiveMetadata.rewriteServices("META-INF/services/p.ab",new byte[]{(byte)0xC3},Map.of("p/ab","p/Class00001")));
    }
}
