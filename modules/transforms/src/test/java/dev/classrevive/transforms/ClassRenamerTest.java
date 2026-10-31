package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ClassRenamerTest {
    byte[] type(String name,String fieldType) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        writer.visitField(ACC_PUBLIC,"other","L"+fieldType+";",null,null).visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    byte[] caller(String name,String target) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"use","(L"+target+";)L"+target+";",null,null);
        method.visitCode(); method.visitVarInsn(ALOAD,0); method.visitInsn(ARETURN); method.visitMaxs(1,1);
        method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    byte[] reflective() {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,"p/caller",null,"java/lang/Object",null);
        var forName=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"dotted","()Ljava/lang/Class;",null,null);
        forName.visitCode(); forName.visitLdcInsn("p.a");
        forName.visitMethodInsn(INVOKESTATIC,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
        forName.visitInsn(ARETURN); forName.visitMaxs(1,0); forName.visitEnd();
        var loadClass=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"loader","(Ljava/lang/ClassLoader;)Ljava/lang/Class;",null,null);
        loadClass.visitCode(); loadClass.visitVarInsn(ALOAD,0); loadClass.visitLdcInsn("p.b");
        loadClass.visitMethodInsn(INVOKEVIRTUAL,"java/lang/ClassLoader","loadClass","(Ljava/lang/String;)Ljava/lang/Class;",false);
        loadClass.visitInsn(ARETURN); loadClass.visitMaxs(2,1); loadClass.visitEnd();
        var array=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"array","()Ljava/lang/Class;",null,null);
        array.visitCode(); array.visitLdcInsn("[Lp/a;");
        array.visitMethodInsn(INVOKESTATIC,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
        array.visitInsn(ARETURN); array.visitMaxs(1,0); array.visitEnd();
        var unrecognized=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"unrecognized","()I",null,null);
        unrecognized.visitCode(); unrecognized.visitLdcInsn("p.a");
        unrecognized.visitMethodInsn(INVOKEVIRTUAL,"java/lang/String","length","()I",false);
        unrecognized.visitInsn(IRETURN); unrecognized.visitMaxs(1,0); unrecognized.visitEnd();
        var external=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"external","()Ljava/lang/Class;",null,null);
        external.visitCode(); external.visitLdcInsn("other.External");
        external.visitMethodInsn(INVOKESTATIC,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
        external.visitInsn(ARETURN); external.visitMaxs(1,0); external.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static Object literal(MethodNode method) {
        for(var instruction:method.instructions.toArray()) if(instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String) return ldc.cst;
        return null;
    }
    byte[] members(String name) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        writer.visitField(ACC_PRIVATE,"x","I",null,null).visitEnd();
        var getter=writer.visitMethod(ACC_PRIVATE,"a","()I",null,null);
        getter.visitCode(); getter.visitVarInsn(ALOAD,0); getter.visitFieldInsn(GETFIELD,name,"x","I"); getter.visitInsn(IRETURN); getter.visitMaxs(1,1); getter.visitEnd();
        var caller=writer.visitMethod(ACC_PUBLIC,"run","()I",null,null);
        caller.visitCode(); caller.visitVarInsn(ALOAD,0); caller.visitMethodInsn(INVOKESPECIAL,name,"a","()I",false); caller.visitInsn(IRETURN); caller.visitMaxs(1,1); caller.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    byte[] overloads(String name,int first,int second) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var noArg=writer.visitMethod(first,"a","()I",null,null);
        noArg.visitCode(); noArg.visitInsn(ICONST_1); noArg.visitInsn(IRETURN); noArg.visitMaxs(1,0); noArg.visitEnd();
        var oneArg=writer.visitMethod(second,"a","(I)I",null,null);
        oneArg.visitCode(); oneArg.visitVarInsn(ILOAD,1); oneArg.visitInsn(IRETURN); oneArg.visitMaxs(1,2); oneArg.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    byte[] privateStatic(String name) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,"a","()V",null,null);
        method.visitCode(); method.visitInsn(RETURN); method.visitMaxs(0,0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    byte[] callerOf(String name,String owner) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"call","()V",null,null);
        method.visitCode(); method.visitMethodInsn(INVOKESTATIC,owner,"a","()V",false); method.visitInsn(RETURN); method.visitMaxs(0,0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    byte[] handleHolder(String name) {
        var writer=new ClassWriter(0); writer.visit(52,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,"a","()V",null,null);
        method.visitCode(); method.visitInsn(RETURN); method.visitMaxs(0,0); method.visitEnd();
        var ref=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"ref","()Ljava/lang/invoke/MethodHandle;",null,null);
        ref.visitCode(); ref.visitLdcInsn(new Handle(H_INVOKESTATIC,name,"a","()V",false)); ref.visitInsn(ARETURN); ref.visitMaxs(1,0); ref.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    byte[] privateConstructor(String name) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,name,null,"java/lang/Object",null);
        var ctor=writer.visitMethod(ACC_PRIVATE,"<init>","()V",null,null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD,0); ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false); ctor.visitInsn(RETURN); ctor.visitMaxs(1,1); ctor.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    byte[] record(String name) {
        var writer=new ClassWriter(0); writer.visit(60,ACC_PUBLIC|ACC_RECORD,name,null,"java/lang/Record",null);
        writer.visitField(ACC_PRIVATE|ACC_FINAL,"x","I",null,null).visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassRenamer.Result renameMembers(Map<String,byte[]> classes,ClassRenamer.MemberRule... rules) {
        return new ClassRenamer().rename(classes,Map.of(),List.of(rules),false);
    }
    @Test void refusesRenamingSerializationMembersAwayOrOnto() {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,"p/s",null,"java/lang/Object",new String[]{"java/io/Serializable"});
        writer.visitField(ACC_PRIVATE|ACC_STATIC|ACC_FINAL,"serialVersionUID","J",null,1L).visitEnd();
        var hook=writer.visitMethod(ACC_PRIVATE,"a","(Ljava/io/ObjectInputStream;)V",null,null);
        hook.visitCode(); hook.visitInsn(RETURN); hook.visitMaxs(0,2); hook.visitEnd(); writer.visitEnd();
        var classes=Map.of("p/s",writer.toByteArray());
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/s","serialVersionUID","J",true,"version")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/s","a","(Ljava/io/ObjectInputStream;)V",false,"readObject")));
        assertEquals(1,renameMembers(classes,new ClassRenamer.MemberRule("p/s","a","(Ljava/io/ObjectInputStream;)V",false,"restore")).members().size());
    }
    @Test void remapsCrossClassDescriptorsAndProvidesInverseMap() {
        byte[] a=type("p/a","p/b"), b=type("p/b","p/a");
        var result=new ClassRenamer().rename(Map.of("p/a",a,"p/b",b),Map.of("p/a","p/First","p/b","p/Second"));
        var node=new ClassNode(); new ClassReader(result.classes().get("p/First")).accept(node,0);
        assertEquals("Lp/Second;",node.fields.getFirst().desc);
        assertEquals("p/a",result.inverse().get("p/First"));
        assertEquals("p/a",new ClassReader(a).getClassName());
        byte[] copy=result.classes().get("p/First"); copy[0]=0;
        assertEquals("p/First",new ClassReader(result.classes().get("p/First")).getClassName());
    }
    @Test void rejectsCollisionsMissingKeysAndPackageMoves() {
        var classes=Map.of("p/a",type("p/a","p/b"),"p/b",type("p/b","p/a"));
        for(var mapping:List.of(Map.of("p/a","p/b"),Map.of("p/a","q/Readable"),Map.of("p/missing","p/Readable"),Map.of("p/a","p/Name","p/b","p/name")))
            assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(classes,mapping));
    }
    @Test void emptyMappingIsByteIdenticalAndPlannerIsDeterministic() {
        var classes=Map.of("p/a",type("p/a","p/a"));
        assertArrayEquals(classes.get("p/a"),new ClassRenamer().rename(classes,Map.of()).classes().get("p/a"));
        assertEquals(ClassRenamer.plan(List.of("p/b","p/a","p/Readable")),ClassRenamer.plan(List.of("p/Readable","p/a","p/b")));
        assertFalse(ClassRenamer.plan(List.of("p/Readable")).containsKey("p/Readable"));
    }
    @Test void remapsCrossClassMethodDescriptors() {
        var result=new ClassRenamer().rename(Map.of("p/a",caller("p/a","p/b"),"p/b",type("p/b","p/a")),
            Map.of("p/b","p/Second"));
        var node=new ClassNode(); new ClassReader(result.classes().get("p/a")).accept(node,0);
        assertEquals("(Lp/Second;)Lp/Second;",node.methods.getFirst().desc);
        assertEquals("p/Second",result.forward().get("p/b"));
    }
    @Test void remapsNestedClassesCoherently() {
        var classes=Map.of("p/a",type("p/a","p/b"),"p/a$B",type("p/a$B","p/a"));
        var result=new ClassRenamer().rename(classes,Map.of("p/a","p/X","p/a$B","p/X$B"));
        assertEquals(Set.of("p/X","p/X$B"),result.classes().keySet());
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(classes,Map.of("p/a","p/X")));
    }
    @Test void disambiguatesCaseOnlyCollisionsWithNestedClassesFollowing() {
        var plan=ClassRenamer.portableIdentities(List.of("p/a","p/A","p/a$1","p/B","p/b_cr2","p/b","q/Ab","q/AB","q/ab"));
        assertEquals(Map.of("p/a","p/a_cr2","p/a$1","p/a_cr2$1","p/b","p/b_cr3","q/Ab","q/Ab_cr2","q/ab","q/ab_cr3"),plan);
        assertEquals(plan,ClassRenamer.portableIdentities(List.of("q/ab","q/AB","q/Ab","p/b","p/b_cr2","p/B","p/a$1","p/A","p/a")));
        assertEquals(Map.of(),ClassRenamer.portableIdentities(List.of("p/a","p/b","p/a$A")));
        var nested=ClassRenamer.portableIdentities(List.of("p/X","p/X$a","p/X$A"));
        assertEquals(Map.of("p/X$a","p/X$a_cr2"),nested);
        var classes=Map.of("p/A",type("p/A","p/a"),"p/a",type("p/a","p/A"),"p/a$1",type("p/a$1","p/a"));
        var result=new ClassRenamer().rename(classes,ClassRenamer.portableIdentities(classes.keySet()));
        assertEquals(Set.of("p/A","p/a_cr2","p/a_cr2$1"),result.classes().keySet());
        var node=new ClassNode(); new ClassReader(result.classes().get("p/A")).accept(node,0);
        assertEquals("Lp/a_cr2;",node.fields.getFirst().desc);
        assertThrows(IllegalArgumentException.class,()->ClassRenamer.portableIdentities(List.of("p/package-info","P/package-info")));
    }
    @Test void makesNameSegmentsJavaIdentifiersAndMovesWholePackages() {
        var plan=ClassRenamer.portableIdentities(List.of("anti-leak/Loader","anti-leak/Loader$1","anti_leak/Other","p/1st","p/1st$x","p/a-b","p/a_b"));
        assertEquals(Map.of("anti-leak/Loader","anti_leak_cr2/Loader","anti-leak/Loader$1","anti_leak_cr2/Loader$1",
            "p/1st","p/_1st","p/1st$x","p/_1st$x","p/a-b","p/a_b_cr2"),plan);
        var classes=Map.of("anti-leak/Loader",type("anti-leak/Loader","anti-leak/Helper"),"anti-leak/Helper",type("anti-leak/Helper","p/Main"),
            "p/Main",type("p/Main","anti-leak/Loader"));
        var mapping=ClassRenamer.portableIdentities(classes.keySet());
        var result=new ClassRenamer().rename(classes,mapping);
        assertEquals(Set.of("anti_leak/Loader","anti_leak/Helper","p/Main"),result.classes().keySet());
        var node=new ClassNode(); new ClassReader(result.classes().get("p/Main")).accept(node,0);
        assertEquals("Lanti_leak/Loader;",node.fields.getFirst().desc);
        // Moving part of a package, or merging two packages, would change package-private access.
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(classes,Map.of("anti-leak/Loader","q/Loader")));
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(classes,
            Map.of("anti-leak/Loader","p/Loader","anti-leak/Helper","p/Helper")));
    }
    @Test void rejectsDescriptorClassesWhenRenaming() {
        var packaged=Map.of("p/a",type("p/a","p/b"),"p/package-info",type("p/package-info","p/a"));
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(packaged,Map.of("p/a","p/X")));
        var module=Map.of("module-info",type("module-info","p/a"),"p/a",type("p/a","p/b"));
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(module,Map.of("p/a","p/X")));
    }
    @Test void rejectsOpaqueAttributesWhenRenaming() {
        var node=new ClassNode(); new ClassReader(type("p/a","p/b")).accept(node,0);
        node.attrs=new ArrayList<>();
        node.attrs.add(new Attribute("UnknownOffsets") {
            @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int stack,int locals) {
                return new ByteVector().putInt(1234);
            }
        });
        var writer=new ClassWriter(0); node.accept(writer); var opaque=writer.toByteArray();
        assertThrows(IllegalArgumentException.class,
            ()->new ClassRenamer().rename(Map.of("p/a",opaque),Map.of("p/a","p/X")));
    }
    @Test void enforcesClassCountAndByteBudgets() {
        var many=new LinkedHashMap<String,byte[]>();
        for(int i=0;i<20001;i++) many.put("p/C"+i,new byte[0]);
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(many,Map.of()));
        var oversized=new LinkedHashMap<String,byte[]>(); oversized.put("p/a",new byte[16*1024*1024+1]);
        assertThrows(IllegalArgumentException.class,()->new ClassRenamer().rename(oversized,Map.of()));
    }
    @Test void plannerSkipsNestedClassesAndLongNames() {
        var planned=ClassRenamer.plan(List.of("p/a","p/a$B","p/XYZ","p/Z"));
        assertTrue(planned.containsKey("p/Z")); assertTrue(planned.get("p/Z").startsWith("p/Class"));
        assertFalse(planned.containsKey("p/a")); assertFalse(planned.containsKey("p/a$B")); assertFalse(planned.containsKey("p/XYZ"));
    }
    @Test void rewritesOnlyLiteralReflectionArgumentsInRecognizedCalls() {
        var classes=Map.of("p/a",type("p/a","p/b"),"p/b",type("p/b","p/a"),"p/caller",reflective());
        var result=new ClassRenamer().rename(classes,Map.of("p/a","p/First","p/b","p/Second"),true);
        var node=new ClassNode(); new ClassReader(result.classes().get("p/caller")).accept(node,0);
        assertEquals("p.First",literal(node.methods.get(0)));
        assertEquals("p.Second",literal(node.methods.get(1)));
        assertEquals("[Lp/First;",literal(node.methods.get(2)));
        assertEquals("p.a",literal(node.methods.get(3)));
        assertEquals("other.External",literal(node.methods.get(4)));
        assertEquals(3,result.reflectionRewrites());
    }
    @Test void reflectionRewriteIsOptInAndCountsZeroByDefault() {
        var classes=Map.of("p/a",type("p/a","p/b"),"p/caller",reflective());
        var result=new ClassRenamer().rename(classes,Map.of("p/a","p/First"));
        var node=new ClassNode(); new ClassReader(result.classes().get("p/caller")).accept(node,0);
        assertEquals("p.a",literal(node.methods.get(0)));
        assertEquals(0,result.reflectionRewrites());
    }
    @Test void renamesPrivateMembersAndRewritesOwnReferences() {
        var result=renameMembers(Map.of("p/a",members("p/a")),
            new ClassRenamer.MemberRule("p/a","a","()I",false,"value"),
            new ClassRenamer.MemberRule("p/a","x","I",true,"count"));
        assertEquals(2,result.members().size());
        assertEquals("count",result.members().get(new ClassRenamer.MemberKey("p/a","x","I",true)));
        assertEquals("value",result.members().get(new ClassRenamer.MemberKey("p/a","a","()I",false)));
        var node=new ClassNode(); new ClassReader(result.classes().get("p/a")).accept(node,0);
        var names=new HashSet<String>(); for(var method:node.methods) names.add(method.name);
        assertEquals(Set.of("value","run"),names);
        String fieldRead=null,callName=null;
        for(var method:node.methods) for(var instruction:method.instructions.toArray()) {
            if(instruction instanceof FieldInsnNode field && field.getOpcode()==GETFIELD) fieldRead=field.name;
            if(instruction instanceof MethodInsnNode call) callName=call.name;
        }
        assertEquals("count",fieldRead); assertEquals("value",callName);
    }
    @Test void wildcardMemberRuleExpandsOverloadsAndRejectsPublicOverload() {
        var result=renameMembers(Map.of("p/a",overloads("p/a",ACC_PRIVATE,ACC_PRIVATE)),
            new ClassRenamer.MemberRule("p/a","a",null,false,"value"));
        assertEquals(2,result.members().size());
        assertThrows(IllegalArgumentException.class,()->renameMembers(Map.of("p/a",overloads("p/a",ACC_PRIVATE,ACC_PUBLIC)),
            new ClassRenamer.MemberRule("p/a","a",null,false,"value")));
    }
    @Test void rewritesMemberReferencesAcrossNestmatesAndHandles() {
        var classes=Map.of("p/outer",privateStatic("p/outer"),"p/outer$Helper",callerOf("p/outer$Helper","p/outer"),"p/ref",handleHolder("p/ref"));
        var result=renameMembers(classes,
            new ClassRenamer.MemberRule("p/outer","a",null,false,"renamed"),
            new ClassRenamer.MemberRule("p/ref","a",null,false,"target"));
        var helper=new ClassNode(); new ClassReader(result.classes().get("p/outer$Helper")).accept(helper,0);
        assertEquals("renamed",((MethodInsnNode)helper.methods.getFirst().instructions.get(0)).name);
        var ref=new ClassNode(); new ClassReader(result.classes().get("p/ref")).accept(ref,0);
        Handle handle=null;
        for(var instruction:ref.methods.getLast().instructions.toArray())
            if(instruction instanceof LdcInsnNode literal && literal.cst instanceof Handle found) handle=found;
        assertNotNull(handle); assertEquals("target",handle.getName());
    }
    /**
     * Phase 2BW: {@code p/n} with a private static native {@code 0a2b()} called from {@code <clinit>}, a private field
     * {@code do}, a private method {@code if} next to an existing {@code if_}, a public {@code 9up()} and a valid {@code ok()}.
     */
    static byte[] nonIdentifierMembers() {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC|ACC_SUPER,"p/n",null,"java/lang/Object",null);
        writer.visitField(ACC_PRIVATE|ACC_STATIC,"do","I",null,null).visitEnd();
        writer.visitMethod(ACC_PRIVATE|ACC_STATIC|ACC_NATIVE,"0a2b","()V",null,null).visitEnd();
        for(String name:List.of("if","if_","ok","9up")) {
            var method=writer.visitMethod((name.equals("9up")?ACC_PUBLIC:ACC_PRIVATE)|ACC_STATIC,name,"()I",null,null);
            method.visitCode(); method.visitFieldInsn(GETSTATIC,"p/n","do","I"); method.visitInsn(IRETURN); method.visitMaxs(1,0); method.visitEnd();
        }
        var init=writer.visitMethod(ACC_STATIC,"<clinit>","()V",null,null);
        init.visitCode(); init.visitMethodInsn(INVOKESTATIC,"p/n","0a2b","()V",false);
        init.visitMethodInsn(INVOKESTATIC,"p/n","if","()I",false); init.visitInsn(POP); init.visitInsn(RETURN); init.visitMaxs(1,0); init.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test void plansPortableNamesForPrivateNonIdentifierMembers() {
        var classes=Map.of("p/n",nonIdentifierMembers(),"p/b",type("p/b","p/n"));
        var plan=ClassRenamer.portableMembers(classes);
        assertEquals(Map.of(new ClassRenamer.MemberKey("p/n","0a2b","()V",false),"_0a2b",
            new ClassRenamer.MemberKey("p/n","do","I",true),"do_",
            new ClassRenamer.MemberKey("p/n","if","()I",false),"if__cr2"),plan.renamed());
        assertEquals(Set.of(new ClassRenamer.MemberKey("p/n","9up","()I",false)),plan.kept().keySet());
        assertTrue(plan.kept().values().iterator().next().startsWith("not private"));
        assertEquals(plan,ClassRenamer.portableMembers(new TreeMap<>(classes)),"deterministic");
        var result=new ClassRenamer().rename(classes,Map.of(),plan.rules(),false);
        var node=new ClassNode(); new ClassReader(result.classes().get("p/n")).accept(node,0);
        assertEquals(List.of("_0a2b","if__cr2","if_","ok","9up","<clinit>"),node.methods.stream().map(m->m.name).toList());
        assertEquals("do_",node.fields.getFirst().name);
        var calls=new ArrayList<String>();
        for(var method:node.methods) for(var instruction:method.instructions.toArray()) {
            if(instruction instanceof MethodInsnNode call) calls.add(call.name);
            if(instruction instanceof FieldInsnNode access) calls.add(access.name);
        }
        assertEquals(List.of("do_","do_","do_","do_","_0a2b","if__cr2"),calls);
    }
    @Test void leavesMemberNamesOfRecordsAndOpaqueClassesUnchanged() {
        var writer=new ClassWriter(0); writer.visit(60,ACC_PUBLIC|ACC_RECORD,"p/r",null,"java/lang/Record",null);
        writer.visitField(ACC_PRIVATE|ACC_FINAL,"1x","I",null,null).visitEnd(); writer.visitEnd();
        var plan=ClassRenamer.portableMembers(Map.of("p/r",writer.toByteArray()));
        assertTrue(plan.renamed().isEmpty()); assertEquals("record component",plan.kept().values().iterator().next());
        assertTrue(ClassRenamer.portableMembers(Map.of("p/a",members("p/a"))).renamed().isEmpty(),"identifiers are never touched");
        var opaque=new ClassWriter(0); opaque.visit(V1_8,ACC_PUBLIC,"p/o",null,"java/lang/Object",null);
        opaque.visitAttribute(new Attribute("Custom") { @Override protected ByteVector write(ClassWriter w,byte[] c,int l,int s,int m) { return new ByteVector(); } });
        opaque.visitField(ACC_PRIVATE,"2y","I",null,null).visitEnd(); opaque.visitEnd();
        var refused=ClassRenamer.portableMembers(Map.of("p/o",opaque.toByteArray()));
        assertTrue(refused.renamed().isEmpty()); assertTrue(refused.kept().values().iterator().next().startsWith("opaque attributes"));
    }
    @Test void memberOnlyMappingRoundTripsThroughTheMappingFile() {
        var plan=MappingFile.parse("method p/n#0a2b()V = _0a2b\nfield p/n#do:I = do_\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(plan.classes().isEmpty());
        var result=new ClassRenamer().rename(Map.of("p/n",nonIdentifierMembers()),plan.classes(),plan.members(),true);
        assertEquals(2,result.members().size());
    }
    @Test void rejectsInvalidOrUnsafeMemberMappings() {
        var classes=Map.of("p/a",members("p/a"),"p/b",type("p/b","p/b"));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/missing","a",null,false,"x")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/a","missing",null,false,"x")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/a","run",null,false,"x")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/a","a",null,false,"bad name")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/a","a","()V",false,"x")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,new ClassRenamer.MemberRule("p/a","a","()I",false,"run")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(classes,
            new ClassRenamer.MemberRule("p/a","a",null,false,"v"),new ClassRenamer.MemberRule("p/a","a","()I",false,"w")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(Map.of("p/c",privateConstructor("p/c")),
            new ClassRenamer.MemberRule("p/c","<init>",null,false,"x")));
        assertThrows(IllegalArgumentException.class,()->renameMembers(Map.of("p/r",record("p/r")),
            new ClassRenamer.MemberRule("p/r","x",null,true,"y")));
        var reprocessed=renameMembers(classes,new ClassRenamer.MemberRule("p/a","a",null,false,"value"));
        assertEquals(Set.of("p/a","p/b"),reprocessed.classes().keySet());
        assertEquals("p/b",new ClassReader(reprocessed.classes().get("p/b")).getClassName());
    }
}
