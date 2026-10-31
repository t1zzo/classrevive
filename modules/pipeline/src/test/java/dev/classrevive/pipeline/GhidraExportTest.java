package dev.classrevive.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GhidraExportTest {
    /** The shape ClassReviveNativeExport.java writes for the fixture DLL (abridged). */
    static String sample() {
        return """
            {"schema_version":1,"tool":"ghidra","ghidra_version":"9.9-test","program":"fixture.dll","format":"Portable Executable (PE)",
             "language":"x86:LE:64:default","compiler":"windows","pointer_size":8,"image_base":"180000000","sha256":"00",
             "analyzed":true,"analysis_timed_out":false,"function_count":5,
             "blocks":[{"name":".text","start":"180001000","size":512,"permissions":"r-x","initialized":true,"entropy_millibits":1200}],
             "imports":[{"library":"KERNEL32.DLL","name":"GetTickCount"}],
             "exports":[{"name":"JNI_OnLoad","address":"180001000","function":true},
                        {"name":"Java_p_Lib_add__II","address":"180001010","function":true}],
             "jni_functions":[{"name":"JNI_OnLoad","address":"180001000","size":11,"signature":"int JNI_OnLoad(void)","calls":["FUN_180001040@180001040"],"created_by_script":false},
                              {"name":"Java_p_Lib_add__II","address":"180001010","size":5,"signature":"int Java_p_Lib_add__II(void)","calls":[],"created_by_script":false}],
             "register_natives_sites":[{"function":"FUN_180001040","address":"180001043","instruction":"CALL qword ptr [RAX + 0x6b8]","depth":1}],
             "strings":[{"address":"1800020a0","type":"string","length":20,"value":"0123456789abcdef0123"}],
             "native_method_tables":[{"entry":"180003000","name":"0123456789abcdef0123","descriptor":"(Ljava/lang/String;)V",
                                      "function_address":"180001030","function":"FUN_180001030"}],
             "decompiled":[{"name":"JNI_OnLoad","address":"180001000","completed":true,"truncated":false,"c":"int JNI_OnLoad(void)\\n{\\n  return 0x10006;\\n}\\n"},
                           {"name":"Java_p_Lib_add__II","address":"180001010","completed":false,"error":"timeout"}]}
            """;
    }

    @Test void readsTheExport() {
        var export = GhidraExport.parse(sample());
        assertEquals("9.9-test", export.ghidraVersion());
        assertEquals(8, export.pointerSize());
        assertTrue(export.analyzed());
        assertEquals(2, export.exports().size());
        assertTrue(export.exports("JNI_OnLoad"));
        assertEquals("CALL qword ptr [RAX + 0x6b8]", export.registerNativesSites().getFirst().instruction());
        assertEquals("(Ljava/lang/String;)V", export.nativeMethodTables().getFirst().descriptor());
        assertTrue(export.decompiled().getFirst().code().contains("return 0x10006;"));
        assertFalse(export.decompiled().get(1).completed());
        assertEquals("timeout", export.decompiled().get(1).error());
        assertEquals(1200, export.blocks().getFirst().entropyMillibits());
    }

    @Test void rejectsOtherDocuments() {
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample().replace("\"schema_version\":1", "\"schema_version\":2")));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample().replace("\"tool\":\"ghidra\"", "\"tool\":\"other\"")));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample().replace("\"function_count\":5", "\"function_count\":\"5\"")));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample().replace("\"imports\":", "\"imported\":")));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse("[1]"));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample() + "x"));
        assertThrows(IllegalArgumentException.class, () -> GhidraExport.parse(sample().replace("\"size\":512", "\"size\":5.5")));
    }
}
