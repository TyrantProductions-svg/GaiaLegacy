package com.gaia.tools.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static com.gaia.tools.model.PrimitiveAttributesTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationReportWriterTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,9,10,13,27,31,127,128,133,155,159,8232,8233})
    void hostileControlsStayVisibleOnOnePhysicalDiagnosticLine(int value) throws Exception {
        String name="_before"+(char)value+"after";var f=new SemanticFixtures();f.attributes().put(name,0);var r=validate(f);
        String canonical=r.report().diagnostics().get(0).path(),text=ValidationReportWriter.text(r);
        assertTrue(text.contains(String.format(java.util.Locale.ROOT,"\\u%04X",value)),text);
        assertEquals(6,text.lines().count());
        assertEquals(canonical,new ObjectMapper().readTree(ValidationReportWriter.json(r)).at("/diagnostics/0/path").asText());
        assertEquals(canonical,r.report().diagnostics().get(0).path());
        assertTrue(text.chars().noneMatch(c->c!=10 && (c<32 || c>=127 && c<=159 || c==8232 || c==8233)));
    }
    @Test void printableUnicodeRemainsReadable() throws Exception {
        var f=new SemanticFixtures();f.attributes().put("_工具_é_星球",0);
        assertTrue(ValidationReportWriter.text(validate(f)).contains("_工具_é_星球"));
    }
    @Test void canonicalJsonContainsProfileHashActualStatisticsAndBounds() throws Exception {
        var r=validate(new SemanticFixtures());String json=ValidationReportWriter.json(r);
        var tree=new ObjectMapper().readTree(json);
        assertEquals("GAIA_GLB_HAND_TOOL_V0",tree.get("profile").asText());assertEquals(0,tree.get("profileVersion").asInt());
        assertEquals(r.sourceSha256(),tree.get("sourceSha256").asText());assertEquals("PASS",tree.get("outcome").asText());
        assertEquals(1,tree.at("/statistics/uniqueTriangleCount").asInt());assertEquals(1,tree.at("/bounds/max/0").asDouble());
        assertFalse(tree.has("timestamp"));assertFalse(tree.has("sourcePath"));
        assertEquals(json,ValidationReportWriter.json(validate(new SemanticFixtures())));
        assertTrue(ValidationReportWriter.text(r).contains("PASS"));
    }
    @Test void failedAndWarningReportsDoNotSerializeUnvalidatedSnapshot() throws Exception {
        var f=new SemanticFixtures();f.attributes().put("_BAD",0);var r=validate(f);
        var tree=new ObjectMapper().readTree(ValidationReportWriter.json(r));assertEquals("FAIL",tree.get("outcome").asText());
        assertTrue(tree.get("bounds").isNull());assertEquals("UNSUPPORTED_ATTRIBUTE",tree.at("/diagnostics/0/code").asText());
        var warn=new SemanticFixtures();warn.repeatTriangles(4001);
        assertEquals("PASS_WITH_WARNINGS",new ObjectMapper().readTree(ValidationReportWriter.json(validate(warn))).get("outcome").asText());
    }
}
