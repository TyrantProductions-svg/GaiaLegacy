package com.gaia.tools.model;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelInspectorMainTest {
    @TempDir Path temp;
    private record Run(int exit,String out,String err) { }
    private static Run run(String... args) {
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        int code=ModelInspectorMain.run(args,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8));
        return new Run(code,out.toString(StandardCharsets.UTF_8),err.toString(StandardCharsets.UTF_8));
    }
    @Test void usageAndIoErrorsAreDistinctAndDoNotEchoMachinePaths() {
        assertEquals(2,run().exit);assertEquals(2,run("a","b").exit);assertEquals(2,run("--watch","a").exit);
        var missing=run(temp.resolve("private-local-name.glb").toString());assertEquals(2,missing.exit);assertFalse(missing.err.contains(temp.toString()));assertFalse(missing.err.contains("Exception"));
        assertEquals(2,run(temp.toString()).exit);
    }
    @Test void passWarningAndFailureUseStableExitCodes() throws Exception {
        Path file=temp.resolve("model.glb");Files.write(file,new SemanticFixtures().bytes());assertEquals(0,run("--json",file.toString()).exit);
        var warn=new SemanticFixtures();warn.repeatTriangles(4001);Files.write(file,warn.bytes());assertEquals(0,run(file.toString()).exit);
        var invalid=new SemanticFixtures();invalid.attributes().put("COLOR_0",1);Files.write(file,invalid.bytes());var result=run("--json",file.toString());
        assertEquals(1,result.exit);assertTrue(result.out.contains("UNSUPPORTED_VERTEX_COLOR"));assertEquals("",result.err);
        Files.write(file,new byte[]{1,2,3});assertEquals(1,run(file.toString()).exit);
    }
    @Test void twoIndependentHeadlessJvmsProduceIdenticalCanonicalJson() throws Exception {
        Path file=temp.resolve("fixture.glb");Files.write(file,EmbeddedImagesTest.textured("png",0).bytes());
        Path first=temp.resolve("first.json"),second=temp.resolve("second.json");
        for(Path output:new Path[]{first,second}) {
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.awt.headless=true","-cp",System.getProperty("inspector.runtimeClasspath"),"com.gaia.tools.model.ModelInspectorMain","--json",file.toString())
                    .redirectOutput(output.toFile()).redirectError(temp.resolve(output.getFileName()+".stderr").toFile()).start();
            boolean done=process.waitFor(30,TimeUnit.SECONDS);
            if(!done)process.destroyForcibly();assertTrue(done,"Headless process timed out");assertEquals(0,process.exitValue());
        }
        assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        assertTrue(Files.readString(first).contains("\"outcome\":\"PASS\""));
    }
}
