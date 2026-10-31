package dev.classrevive.model;

import java.util.List;

public record ClassInfo(String name, String superName, int majorVersion, int minorVersion,
                        int access, List<String> interfaces, List<MemberInfo> fields,
                        List<MemberInfo> methods) {
    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
    }
    public int javaVersion() { return majorVersion - 44; }
}
