package dev.classrevive.gui;

import java.util.*;

/** Phase G4: method keys ({@code name + descriptor}) as people read them: {@code twice(int)}, {@code Circle(double)}. */
final class Descriptors {
    private Descriptors() {}

    static String pretty(String className, String method) {
        int paren = method.indexOf('(');
        if (paren < 0) return Ui.visible(method);
        String name = method.substring(0, paren);
        if (name.equals("<init>")) name = MethodLocator.simpleName(className).isEmpty() ? "<init>" : MethodLocator.simpleName(className);
        if (name.equals("<clinit>")) return "static { }";
        var types = new ArrayList<String>();
        String descriptor = method.substring(paren);
        for (int i = 1; i < descriptor.length() && descriptor.charAt(i) != ')'; ) {
            int dimensions = 0;
            while (descriptor.charAt(i) == '[') { dimensions++; i++; }
            String type;
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                String full = descriptor.substring(i + 1, end);
                type = full.substring(Math.max(full.lastIndexOf('/'), full.lastIndexOf('$')) + 1);
                i = end + 1;
            } else {
                type = switch (c) { case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short"; case 'I' -> "int";
                    case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double"; default -> String.valueOf(c); };
                i++;
            }
            types.add(type + "[]".repeat(dimensions));
        }
        return Ui.visible(name) + "(" + Ui.visible(String.join(", ", types)) + ")";
    }
}
