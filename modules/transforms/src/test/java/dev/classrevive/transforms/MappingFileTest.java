package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MappingFileTest {
    private static MappingFile.Plan parse(String text) {
        return MappingFile.parse(text.getBytes(StandardCharsets.UTF_8));
    }
    @Test void parsesClassMethodAndFieldRulesWithDottedNames() {
        var plan=parse("# comment\r\n\nclass p.ab=p.Class00001\nclass p/cd=p/Class00002\nmethod p.ab#run()V=reset\nmethod p/cd#x=allX\nfield p/cd#count:I=total\nfield p.ab#b=bl\n");
        assertEquals("p/Class00001",plan.classes().get("p/ab"));
        assertEquals("p/Class00002",plan.classes().get("p/cd"));
        assertEquals(List.of(
            new ClassRenamer.MemberRule("p/ab","run","()V",false,"reset"),
            new ClassRenamer.MemberRule("p/ab","b",null,true,"bl"),
            new ClassRenamer.MemberRule("p/cd","x",null,false,"allX"),
            new ClassRenamer.MemberRule("p/cd","count","I",true,"total")),plan.members());
    }
    @Test void acceptsBomArrayDescriptorsAndBlankLines() {
        var plan=parse("\uFEFF\nfield p/a#arr:[I=items\nmethod p/a#m(I[Ljava/lang/String;)V=go\n");
        assertEquals(List.of(
            new ClassRenamer.MemberRule("p/a","m","(I[Ljava/lang/String;)V",false,"go"),
            new ClassRenamer.MemberRule("p/a","arr","[I",true,"items")),plan.members());
    }
    @Test void rejectsMalformedLinesAndDuplicates() {
        for (String text : List.of(
            "class p/a p/b", "bogus p/a=p/b", "class p/a=", "method p/a#()V=x", "field p/a#x:bad=x",
            "method p/a#m(foo)=x", "field p/a#x:I=", "class p/a=p/b extra", "# nothing\n", "",
            "class p/a=p/b\nclass p/a=p/c", "method p/a#m()V=x\nmethod p/a#m()V=y")) {
            assertThrows(IllegalArgumentException.class,()->parse(text),text);
        }
    }
    @Test void rejectsLimitedOrInvalidInput() {
        var many=new StringBuilder();
        for (int i=0;i<20001;i++) many.append("class p.C").append(i).append("=p.X").append(i).append('\n');
        assertThrows(IllegalArgumentException.class,()->parse(many.toString()));
        assertThrows(IllegalArgumentException.class,()->MappingFile.parse(new byte[MappingFile.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->MappingFile.parse(new byte[]{(byte)0xC3}));
    }
}
