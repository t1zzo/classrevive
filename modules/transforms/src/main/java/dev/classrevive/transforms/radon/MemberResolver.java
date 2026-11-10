package dev.classrevive.transforms.radon;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/**
 * Phase 2DK: the methods and fields a class declares or inherits, from the jar's own class files and the JDK's (platform
 * loader, loaded without initialization, as {@code Hierarchy} does for frames). Radon's heavy invokedynamic sites name
 * their target only by an owner and two hashes; this lists the candidates the hashes are checked against. A class that is
 * neither in the jar nor in the JDK (a library the jar needs) is unknown, and a site that needs it is left alone.
 */
final class MemberResolver {
    record Member(String owner, String name, String descriptor, int access) {
        boolean isStatic() { return (access & Opcodes.ACC_STATIC) != 0; }
    }
    private record Shape(String superName, List<String> interfaces, boolean isInterface, List<Member> methods, List<Member> fields) {}

    private final Map<String,ClassNode> classes;
    private final Map<String,Optional<Shape>> cache = new HashMap<>();

    MemberResolver(Map<String,ClassNode> classes) { this.classes = classes; }

    /** Whether the type is known and an interface; empty when the type is unknown. */
    Optional<Boolean> isInterface(String owner) { return shape(owner).map(Shape::isInterface); }

    /**
     * Every method of {@code owner} and its supertypes with the given name hash and descriptor hash (constructors and
     * class initializers excluded), most specific first. Empty when a type of the hierarchy is unknown, because a match
     * there could not be ruled out.
     */
    Optional<List<Member>> methods(String owner, int nameHash, int descriptorHash) {
        return search(owner, true, member -> !member.name.startsWith("<") && member.name.hashCode() == nameHash
            && RadonCipher.descriptorHash(member.descriptor) == descriptorHash);
    }

    Optional<List<Member>> fields(String owner, int nameHash, int typeHash) {
        return search(owner, false, member -> member.name.hashCode() == nameHash && RadonCipher.typeHash(member.descriptor) == typeHash);
    }

    private Optional<List<Member>> search(String owner, boolean methods, java.util.function.Predicate<Member> filter) {
        var found = new ArrayList<Member>(); var pending = new ArrayDeque<String>(List.of(owner)); var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            if (!seen.add(type)) continue;
            if (seen.size() > 1000) return Optional.empty();
            var shape = shape(type);
            if (shape.isEmpty()) return Optional.empty();
            for (var member : methods ? shape.get().methods : shape.get().fields) if (filter.test(member)) found.add(member);
            if (shape.get().superName != null) pending.addLast(shape.get().superName);
            shape.get().interfaces.forEach(pending::addLast);
        }
        return Optional.of(found);
    }

    private Optional<Shape> shape(String name) {
        return cache.computeIfAbsent(name, key -> {
            var node = classes.get(key);
            if (node != null) {
                var methods = node.methods.stream().map(m -> new Member(key, m.name, m.desc, m.access)).toList();
                var fields = node.fields.stream().map(f -> new Member(key, f.name, f.desc, f.access)).toList();
                return Optional.of(new Shape(node.superName, node.interfaces == null ? List.of() : node.interfaces,
                    (node.access & Opcodes.ACC_INTERFACE) != 0, methods, fields));
            }
            if (key.startsWith("[")) return Optional.empty();
            try {
                Class<?> type = Class.forName(key.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                var methods = new ArrayList<Member>();
                for (var method : type.getDeclaredMethods())
                    methods.add(new Member(key, method.getName(), Type.getMethodDescriptor(method), method.getModifiers()));
                var fields = new ArrayList<Member>();
                for (var field : type.getDeclaredFields())
                    fields.add(new Member(key, field.getName(), Type.getDescriptor(field.getType()), field.getModifiers()));
                var interfaces = new ArrayList<String>();
                for (var item : type.getInterfaces()) interfaces.add(Type.getInternalName(item));
                return Optional.of(new Shape(type.getSuperclass() == null ? null : Type.getInternalName(type.getSuperclass()),
                    interfaces, type.isInterface(), methods, fields));
            } catch (ClassNotFoundException | LinkageError | SecurityException unknown) {
                return Optional.empty();
            }
        });
    }
}
