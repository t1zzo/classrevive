package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.*;
import java.util.*;

/** Coherent class-set remapping with explicit private-member renaming. Runtime reflection, external callers and resources are not rewritten. */
public final class ClassRenamer {
    /** Explicit member rule; a null descriptor renames every member with that name and kind. */
    public record MemberRule(String owner,String name,String descriptor,boolean field,String target) {
        public MemberRule {
            Objects.requireNonNull(owner,"owner"); Objects.requireNonNull(name,"name"); Objects.requireNonNull(target,"target");
        }
    }
    public record MemberKey(String owner,String name,String descriptor,boolean field) implements Comparable<MemberKey> {
        public MemberKey {
            Objects.requireNonNull(owner,"owner"); Objects.requireNonNull(name,"name"); Objects.requireNonNull(descriptor,"descriptor");
        }
        @Override public int compareTo(MemberKey other) {
            int order=owner.compareTo(other.owner); if(order!=0) return order;
            order=Boolean.compare(field,other.field); if(order!=0) return order;
            order=name.compareTo(other.name); if(order!=0) return order;
            return descriptor.compareTo(other.descriptor);
        }
    }
    public record Result(Map<String,byte[]> classes,Map<String,String> forward,Map<String,String> inverse,Map<MemberKey,String> members,String limitation,int reflectionRewrites) {
        public Result {
            classes=copy(classes); forward=Collections.unmodifiableMap(new TreeMap<>(forward)); inverse=Collections.unmodifiableMap(new TreeMap<>(inverse));
            members=Collections.unmodifiableMap(new TreeMap<>(members));
        }
        @Override public Map<String,byte[]> classes() { return copy(classes); }
        private static Map<String,byte[]> copy(Map<String,byte[]> input) {
            var result=new TreeMap<String,byte[]>(); input.forEach((name,bytes)->result.put(name,bytes.clone())); return Collections.unmodifiableMap(result);
        }
    }
    public Result rename(Map<String,byte[]> classes,Map<String,String> requested) { return rename(classes,requested,List.of(),false); }
    public Result rename(Map<String,byte[]> classes,Map<String,String> requested,boolean rewriteReflectionStrings) { return rename(classes,requested,List.of(),rewriteReflectionStrings); }
    public Result rename(Map<String,byte[]> classes,Map<String,String> requested,List<MemberRule> memberRules,boolean rewriteReflectionStrings) {
        if(classes.isEmpty() || classes.size()>20000) throw new IllegalArgumentException("Require 1..20000 classes");
        if(memberRules.size()>50000) throw new IllegalArgumentException("Member rule limit exceeded");
        var mapping=new TreeMap<>(requested); var input=new TreeMap<String,byte[]>(); long total=0;
        for(var entry:classes.entrySet()) {
            byte[] bytes=entry.getValue(); total+=bytes.length;
            if(bytes.length>16*1024*1024 || total>128L*1024*1024) throw new IllegalArgumentException("Class byte budget exceeded");
            if(!validOriginal(entry.getKey()) || !new ClassReader(bytes).getClassName().equals(entry.getKey())) throw new IllegalArgumentException("Class identity mismatch");
            input.put(entry.getKey(),bytes.clone());
        }
        for(var entry:mapping.entrySet()) {
            String from=entry.getKey(),to=entry.getValue();
            if(!input.containsKey(from) || !validTarget(to) || special(from) || special(to))
                throw new IllegalArgumentException("Invalid, missing or cross-package class mapping");
            for(String nested:input.keySet()) if(nested.startsWith(from+"$") &&
                !mapping.getOrDefault(nested,nested).startsWith(to+"$"))
                throw new IllegalArgumentException("Nested classes require coherent explicit mappings");
        }
        // A class may leave its package only when its whole package moves with it (Phase 2BV): every class of a source
        // package lands in one target package that no other package uses, so package-private access is unchanged.
        var packages=new HashMap<String,String>(); var targets=new HashMap<String,String>();
        for(String name:input.keySet()) {
            String source=packageName(name), target=packageName(mapping.getOrDefault(name,name));
            String previous=packages.putIfAbsent(source,target), owner=targets.putIfAbsent(target,source);
            if((previous!=null && !previous.equals(target)) || (owner!=null && !owner.equals(source)))
                throw new IllegalArgumentException("Invalid, missing or cross-package class mapping");
        }
        var expanded=expandMemberRules(input,memberRules);
        var outputs=new HashSet<String>();
        for(String name:input.keySet()) if(!outputs.add(mapping.getOrDefault(name,name).toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Output class name collision");
        var result=new TreeMap<String,byte[]>(); var remapper=new Renamer(mapping,expanded); int reflectionRewrites=0;
        for(var entry:input.entrySet()) {
            if(mapping.isEmpty() && expanded.isEmpty()) { result.put(entry.getKey(),entry.getValue()); continue; }
            // Descriptors never declare the private members of other classes, so they pass through when no class moves (Phase 2BW).
            if(special(entry.getKey()) && mapping.isEmpty()) { result.put(entry.getKey(),entry.getValue()); continue; }
            if(special(entry.getKey())) throw new IllegalArgumentException("Descriptor classes require archive-aware renaming");
            var reader=new ClassReader(entry.getValue()); var node=new ClassNode(); reader.accept(node,0);
            if(IntegerConstantPass.hasOpaqueAttributes(node)) throw new IllegalArgumentException("Opaque attributes prevent safe metadata rewriting");
            if(rewriteReflectionStrings) reflectionRewrites+=rewriteReflectionStrings(node,mapping);
            var writer=new ClassWriter(reader,0); node.accept(new ClassRemapper(writer,remapper));
            byte[] bytes=writer.toByteArray();
            if(!readable(bytes)) {
                // Phase 2CD: a StackMapTable whose uninitialized entries do not point at a NEW (Skidfuscator writes
                // Uninitialized(0)) comes out of the copy unreadable. Such a class never verified; it is written without
                // frames (the decompilers do not need them) rather than failing the whole job.
                var plain=new ClassNode(); new ClassReader(entry.getValue()).accept(plain,ClassReader.SKIP_FRAMES);
                if(rewriteReflectionStrings) rewriteReflectionStrings(plain,mapping);
                var unframed=new ClassWriter(ClassWriter.COMPUTE_MAXS); plain.accept(new ClassRemapper(unframed,remapper));
                bytes=unframed.toByteArray();
            }
            if(bytes.length>16*1024*1024) throw new IllegalArgumentException("Renamed class exceeds byte limit");
            result.put(mapping.getOrDefault(entry.getKey(),entry.getKey()),bytes);
        }
        var inverse=new TreeMap<String,String>(); mapping.forEach((from,to)->inverse.put(to,from));
        return new Result(result,mapping,inverse,expanded,"Names are generated aliases or explicit mappings, not original source names. Only literal Class.forName/ClassLoader.loadClass arguments are optionally rewritten; reflection, serialization, annotations, resources and external callers are not handled by this core. Behavior NOT_VERIFIED.",reflectionRewrites);
    }
    /** True when ASM reads the class back with its frames. */
    static boolean readable(byte[] bytes) {
        try { new ClassReader(bytes).accept(new ClassNode(),0); return true; }
        catch(RuntimeException malformed) { return false; }
    }
    private static TreeMap<MemberKey,String> expandMemberRules(Map<String,byte[]> input,List<MemberRule> rules) {
        var expanded=new TreeMap<MemberKey,String>();
        var byOwner=new TreeMap<String,List<MemberRule>>();
        for(var rule:rules) {
            if(!input.containsKey(rule.owner())) throw new IllegalArgumentException("Member mapping references an unknown class");
            if(special(rule.owner())) throw new IllegalArgumentException("Member mapping targets a descriptor class");
            if(!validMemberName(rule.target())) throw new IllegalArgumentException("Invalid member target name");
            byOwner.computeIfAbsent(rule.owner(),owner->new ArrayList<>()).add(rule);
        }
        for(var entry:byOwner.entrySet()) {
            String owner=entry.getKey(); var node=new ClassNode(); new ClassReader(input.get(owner)).accept(node,0);
            if(IntegerConstantPass.hasOpaqueAttributes(node)) throw new IllegalArgumentException("Opaque attributes prevent safe member renaming");
            boolean record=(node.access&Opcodes.ACC_RECORD)!=0;
            var present=new HashSet<MemberKey>();
            for(var field:node.fields) present.add(new MemberKey(owner,field.name,field.desc,true));
            for(var method:node.methods) present.add(new MemberKey(owner,method.name,method.desc,false));
            var exact=new TreeMap<MemberKey,String>();
            var wildcard=new ArrayList<MemberRule>();
            for(var rule:entry.getValue()) {
                if(rule.descriptor()==null) wildcard.add(rule);
                else exact.put(new MemberKey(owner,rule.name(),rule.descriptor(),rule.field()),rule.target());
            }
            for(var same:exact.entrySet()) {
                if(!present.contains(same.getKey())) throw new IllegalArgumentException("Member mapping matches no member in "+owner);
                checkRenameable(owner,node,same.getKey(),record);
                expanded.put(same.getKey(),same.getValue());
            }
            for(var rule:wildcard) {
                int matches=0;
                for(var key:present) if(key.field()==rule.field() && key.name().equals(rule.name())) {
                    if(exact.containsKey(key)) throw new IllegalArgumentException("Overlapping member mapping in "+owner);
                    checkRenameable(owner,node,key,record);
                    expanded.put(key,rule.target()); matches++;
                }
                if(matches==0) throw new IllegalArgumentException("Member mapping matches no member in "+owner);
            }
            for(var renamed:expanded.entrySet()) if(renamed.getKey().owner().equals(owner)
                && serializationMember(renamed.getValue(),renamed.getKey().descriptor(),renamed.getKey().field()))
                throw new IllegalArgumentException("Serialization member names cannot be introduced in "+owner);
            var methods=new HashSet<String>(); var fields=new HashSet<String>();
            for(var key:present) {
                String target=expanded.getOrDefault(key,key.name());
                boolean added=key.field()?fields.add(target+"\u0000"+key.descriptor()):methods.add(target+"\u0000"+key.descriptor());
                if(!added) throw new IllegalArgumentException("Member rename collision in "+owner);
            }
        }
        return expanded;
    }
    private static void checkRenameable(String owner,ClassNode node,MemberKey key,boolean record) {
        int access=-1;
        if(key.field()) { for(var field:node.fields) if(field.name.equals(key.name()) && field.desc.equals(key.descriptor())) access=field.access; }
        else { for(var method:node.methods) if(method.name.equals(key.name()) && method.desc.equals(key.descriptor())) access=method.access; }
        if((access&Opcodes.ACC_PRIVATE)==0) throw new IllegalArgumentException("Only private members can be renamed in "+owner);
        if(key.name().startsWith("<")) throw new IllegalArgumentException("Constructors and initializers cannot be renamed in "+owner);
        if(key.field() && record) throw new IllegalArgumentException("Record members cannot be renamed in "+owner);
        if(serializationMember(key.name(),key.descriptor(),key.field()))
            throw new IllegalArgumentException("Serialization members are looked up by name and cannot be renamed in "+owner);
    }
    /**
     * Private members Java serialization finds by exact name and descriptor. Renaming one away from, or onto, these
     * names silently changes serialization behavior even though the class still verifies.
     */
    static boolean serializationMember(String name,String descriptor,boolean field) {
        if(field) return name.equals("serialVersionUID")&&descriptor.equals("J")
            || name.equals("serialPersistentFields")&&descriptor.equals("[Ljava/io/ObjectStreamField;");
        return name.equals("writeObject")&&descriptor.equals("(Ljava/io/ObjectOutputStream;)V")
            || name.equals("readObject")&&descriptor.equals("(Ljava/io/ObjectInputStream;)V")
            || name.equals("readObjectNoData")&&descriptor.equals("()V")
            || name.equals("writeReplace")&&descriptor.equals("()Ljava/lang/Object;")
            || name.equals("readResolve")&&descriptor.equals("()Ljava/lang/Object;");
    }
    private static boolean validMemberName(String name) {
        return name!=null && name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*") && !name.startsWith("<");
    }
    /** Rewrites only member access whose declaring owner is renamed; private members have no inherited resolution. */
    private static final class Renamer extends Remapper {
        private final Map<String,String> classes;
        private final Map<MemberKey,String> members;
        Renamer(Map<String,String> classes,Map<MemberKey,String> members) { this.classes=classes; this.members=members; }
        @Override public String map(String internalName) { return classes.getOrDefault(internalName,internalName); }
        @Override public String mapMethodName(String owner,String name,String descriptor) {
            String target=members.get(new MemberKey(owner,name,descriptor,false)); return target==null?name:target;
        }
        @Override public String mapFieldName(String owner,String name,String descriptor) {
            String target=members.get(new MemberKey(owner,name,descriptor,true)); return target==null?name:target;
        }
    }
    /** Rewrites only literal class names consumed directly by Class.forName(String) or ClassLoader.loadClass(String). */
    private static int rewriteReflectionStrings(ClassNode type,Map<String,String> mapping) {
        int rewrites=0;
        for(var method:type.methods) for(var instruction:method.instructions.toArray()) {
            if(!(instruction instanceof MethodInsnNode call)) continue;
            boolean forName=call.getOpcode()==Opcodes.INVOKESTATIC && call.owner.equals("java/lang/Class") && call.name.equals("forName")
                && call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;");
            boolean loadClass=call.getOpcode()==Opcodes.INVOKEVIRTUAL && call.owner.equals("java/lang/ClassLoader") && call.name.equals("loadClass")
                && call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;");
            if(!forName&&!loadClass) continue;
            var argument=previousReal(instruction);
            if(!(argument instanceof LdcInsnNode literal) || !(literal.cst instanceof String text)) continue;
            String replacement=remapClassName(text,mapping);
            if(replacement!=null && !replacement.equals(text)) { literal.cst=replacement; rewrites++; }
        }
        return rewrites;
    }
    private static AbstractInsnNode previousReal(AbstractInsnNode node) {
        var current=node.getPrevious();
        while(current!=null && (current instanceof LabelNode || current instanceof LineNumberNode || current instanceof FrameNode)) current=current.getPrevious();
        return current;
    }
    private static String remapClassName(String text,Map<String,String> mapping) {
        String mapped=mapping.get(text);
        if(mapped!=null) return mapped;
        mapped=mapping.get(text.replace('.','/'));
        if(mapped!=null) return mapped.replace('/','.');
        if(text.startsWith("[") && text.endsWith(";")) {
            int start=text.indexOf('L');
            if(start>=0) { mapped=mapping.get(text.substring(start+1,text.length()-1)); if(mapped!=null) return text.substring(0,start+1)+mapped+";"; }
        }
        return null;
    }
    public static Map<String,String> plan(Collection<String> names) {
        if(names.size()>20000) throw new IllegalArgumentException("Class count limit exceeded");
        var sorted=new TreeSet<>(names); var reserved=new HashSet<String>();
        for(String name:sorted) { if(!validOriginal(name)) throw new IllegalArgumentException("Invalid class identity"); reserved.add(name.toLowerCase(Locale.ROOT)); }
        var result=new TreeMap<String,String>(); int counter=1;
        for(String name:sorted) {
            String simple=name.substring(name.lastIndexOf('/')+1);
            if(simple.length()>2 || simple.contains("$") || special(name) || sorted.stream().anyMatch(other->other.startsWith(name+"$"))) continue;
            String alias;
            do { alias=packageName(name)+"Class"+String.format(Locale.ROOT,"%05d",counter++); } while(reserved.contains(alias.toLowerCase(Locale.ROOT)));
            reserved.add(alias.toLowerCase(Locale.ROOT)); result.put(name,alias);
        }
        return Collections.unmodifiableMap(result);
    }
    /**
     * Phase 2BV: the smallest coherent mapping that gives every class a name javac can write on any file system.
     * <ol>
     * <li>A package or class name segment that is no Java identifier (the JVM allows {@code anti-leak/Loader}) gets each
     * offending character replaced by {@code _} and, when it starts with a digit, a leading {@code _}. A package moves as
     * a whole; a package name already used gets the suffix {@code _crN}.</li>
     * <li>In each group of names equal ignoring letter case (after step 1), the first in {@link String} order keeps its
     * name (upper case sorts first, so {@code app/A} stays and {@code app/a} moves); every other one gets the stable
     * suffix {@code _cr2}, {@code _cr3}, ... (the first one free ignoring case).</li>
     * </ol>
     * Nested classes ({@code name$...}) always follow their outer class. Deterministic for a given class set; names that
     * are already portable and distinct ignoring case are never touched.
     */
    public static Map<String,String> portableIdentities(Collection<String> names) {
        if(names.size()>20000) throw new IllegalArgumentException("Class count limit exceeded");
        var effective=new TreeMap<String,String>();
        for(String name:new TreeSet<>(names)) { if(!validOriginal(name)) throw new IllegalArgumentException("Invalid class identity"); effective.put(name,name); }
        var packages=new TreeSet<String>(); effective.keySet().forEach(name->packages.add(packageName(name)));
        var usedPackages=new HashSet<String>(); packages.forEach(name->usedPackages.add(caseKey(name)));
        var movedPackages=new HashMap<String,String>();
        for(String source:packages) {
            String target=portablePath(source);
            if(target.equals(source)) continue;
            String base=target.substring(0,target.length()-1), candidate=target;
            for(int suffix=2;usedPackages.contains(caseKey(candidate));suffix++) candidate=base+"_cr"+suffix+"/";
            usedPackages.add(caseKey(candidate)); movedPackages.put(source,candidate);
        }
        for(var entry:effective.entrySet()) {
            String name=entry.getKey(), source=packageName(name), simple=name.substring(source.length());
            entry.setValue(movedPackages.getOrDefault(source,source)+(special(name)?simple:portableSegment(simple)));
        }
        var taken=new HashMap<String,Integer>();
        effective.values().forEach(name->taken.merge(caseKey(name),1,Integer::sum));
        boolean changed=true;
        while(changed) {
            changed=false;
            var groups=new TreeMap<String,List<String>>();
            effective.forEach((original,current)->groups.computeIfAbsent(caseKey(current),key->new ArrayList<>()).add(original));
            var touched=new HashSet<String>();
            for(var group:groups.values()) {
                if(group.size()<2 || group.stream().anyMatch(touched::contains)) continue;
                // Equal names after step 1: an original that was already portable keeps its name.
                group.sort(Comparator.comparing((String original)->effective.get(original))
                    .thenComparing(original->!original.equals(effective.get(original))).thenComparing(Comparator.naturalOrder()));
                for(String original:group.subList(1,group.size())) {
                    String from=effective.get(original);
                    if(special(from)) throw new IllegalArgumentException("Module and package descriptors that collide cannot be renamed: "+original);
                    String to=null;
                    for(int suffix=2;to==null;suffix++) {
                        String candidate=from+"_cr"+suffix, key=caseKey(candidate);
                        if(!taken.containsKey(key) && taken.keySet().stream().noneMatch(other->other.startsWith(key+"$"))) to=candidate;
                    }
                    for(var entry:effective.entrySet()) {
                        if(!entry.getKey().equals(original) && !entry.getKey().startsWith(original+"$")) continue;
                        String current=entry.getValue(), moved=to+current.substring(from.length());
                        taken.computeIfPresent(caseKey(current),(key,count)->count==1?null:count-1);
                        taken.merge(caseKey(moved),1,Integer::sum);
                        entry.setValue(moved); touched.add(entry.getKey());
                    }
                    changed=true;
                }
            }
        }
        var result=new TreeMap<String,String>();
        effective.forEach((original,current)->{ if(!original.equals(current)) result.put(original,current); });
        return Collections.unmodifiableMap(result);
    }
    /** Phase 2BW member plan: {@code renamed} feeds {@link #rename}; {@code kept} are non-portable names left unchanged, with the reason. */
    public record MemberPlan(Map<MemberKey,String> renamed,Map<MemberKey,String> kept) {
        public MemberPlan { renamed=Collections.unmodifiableMap(new TreeMap<>(renamed)); kept=Collections.unmodifiableMap(new TreeMap<>(kept)); }
        public List<MemberRule> rules() {
            return renamed.entrySet().stream().map(e->new MemberRule(e.getKey().owner(),e.getKey().name(),e.getKey().descriptor(),e.getKey().field(),e.getValue())).toList();
        }
    }
    /** Reserved keywords and literals javac never accepts as a member name ({@code yield} cannot be called unqualified since Java 14). */
    private static final Set<String> RESERVED=Set.of("abstract","assert","boolean","break","byte","case","catch","char","class","const",
        "continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import",
        "instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static",
        "strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while","true","false",
        "null","_","yield");
    /**
     * Phase 2BW: portable names for fields and methods whose names are no Java identifiers ({@code 0a2b4acb...}, which
     * the JVM allows) or are reserved words. Only private members are renamed: they are never inherited or overridden, so
     * every reference names the declaring class and {@link #rename} rewrites them all. The new name is the
     * {@link #portableSegment} image (offending characters become {@code _}, a leading digit gets a {@code _} prefix, a
     * reserved word gets a {@code _} suffix), with {@code _crN} when a member of the same kind in that class already uses it.
     * Non-private members, and names the mapping file cannot express, are left unchanged and listed in {@code kept}.
     * Deterministic for a given class set; reads only member headers and never runs the classes.
     */
    public static MemberPlan portableMembers(Map<String,byte[]> classes) {
        if(classes.size()>20000) throw new IllegalArgumentException("Class count limit exceeded");
        var renamed=new TreeMap<MemberKey,String>(); var kept=new TreeMap<MemberKey,String>();
        var nodes=new TreeMap<String,ClassNode>(); String opaque=null;
        for(var type:classes.entrySet()) {
            var node=new ClassNode(); new ClassReader(type.getValue()).accept(node,0);
            if(!node.name.equals(type.getKey())) throw new IllegalArgumentException("Class identity mismatch");
            nodes.put(type.getKey(),node);
            if(opaque==null && IntegerConstantPass.hasOpaqueAttributes(node)) opaque=type.getKey();
        }
        for(var type:nodes.entrySet()) {
            String owner=type.getKey(); var node=type.getValue();
            if(special(owner)) continue;
            var members=new ArrayList<MemberKey>(); var access=new HashMap<MemberKey,Integer>();
            for(var field:node.fields) { var key=new MemberKey(owner,field.name,field.desc,true); members.add(key); access.put(key,field.access); }
            for(var method:node.methods) { var key=new MemberKey(owner,method.name,method.desc,false); members.add(key); access.put(key,method.access); }
            Collections.sort(members);
            var usedFields=new HashSet<String>(); var usedMethods=new HashSet<String>();
            for(var key:members) (key.field()?usedFields:usedMethods).add(key.name());
            boolean record=(node.access&Opcodes.ACC_RECORD)!=0;
            for(var key:members) {
                String name=key.name();
                if(name.startsWith("<") || portableMember(name)) continue;
                String reason=null;
                if(opaque!=null) reason="opaque attributes in "+opaque+" prevent safe rewriting";
                else if((access.get(key)&Opcodes.ACC_PRIVATE)==0) reason="not private: it may be inherited or overridden";
                else if(key.field() && record) reason="record component";
                else if(serializationMember(name,key.descriptor(),key.field())) reason="serialization member";
                else if(!mappingExpressible(name,key.field())) reason="name the mapping file cannot express";
                if(reason!=null) { kept.put(key,reason); continue; }
                String base=RESERVED.contains(name)?name+"_":portableSegment(name), target=base;
                var used=key.field()?usedFields:usedMethods;
                for(int suffix=2;used.contains(target) || RESERVED.contains(target) || serializationMember(target,key.descriptor(),key.field());suffix++)
                    target=base+"_cr"+suffix;
                used.add(target); renamed.put(key,target);
            }
        }
        return new MemberPlan(renamed,kept);
    }
    /** Whether javac accepts the name as a field or method name as it is. */
    static boolean portableMember(String name) { return !name.isEmpty() && portableSegment(name).equals(name) && !RESERVED.contains(name); }
    /** {@link MappingFile} splits on whitespace, {@code =}, {@code #} and the descriptor delimiter. */
    private static boolean mappingExpressible(String name,boolean field) {
        return name.length()<=1024 && name.chars().noneMatch(c->c<=' ' || Character.isWhitespace(c) || Character.isISOControl(c)
            || Character.isSurrogate((char)c) || c=='=' || c=='#' || c==(field?':':'('));
    }
    /** A package path ({@code a/b/}, or empty) with every segment made a Java identifier. */
    private static String portablePath(String path) {
        if(path.isEmpty()) return path;
        var result=new StringBuilder();
        for(String segment:path.substring(0,path.length()-1).split("/",-1)) result.append(portableSegment(segment)).append('/');
        return result.toString();
    }
    /** Character-wise, so {@code a-b$c} still starts with the image of {@code a-b} plus {@code $}. */
    private static String portableSegment(String segment) {
        var result=new StringBuilder(segment.length()+1);
        if(!segment.isEmpty() && !(Character.isLetter(segment.charAt(0)) || segment.charAt(0)=='_' || segment.charAt(0)=='$')) result.append('_');
        segment.codePoints().forEach(c->result.appendCodePoint(Character.isLetterOrDigit(c) || c=='_' || c=='$' ? c : '_'));
        return result.toString();
    }
    private static String caseKey(String name) { return java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    private static String packageName(String name) { return name.substring(0,name.lastIndexOf('/')+1); }
    private static boolean special(String name) { return name.equals("module-info") || name.endsWith("/package-info") || name.equals("package-info"); }
    private static boolean validOriginal(String name) { return name!=null && name.length()<=4096 && name.matches("[\\p{L}\\p{N}_$-]+(/[\\p{L}\\p{N}_$-]+)*"); }
    private static boolean validTarget(String name) { return name!=null && name.length()<=4096 && name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(/[\\p{L}_$][\\p{L}\\p{N}_$]*)*"); }
}
