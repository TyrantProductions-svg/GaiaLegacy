package com.gaia.tools.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProfileAndReportTest {
    @Test void longCommonPrefixDoesNotMergeCanonicalDiagnostics() throws Exception {
        var f=new SemanticFixtures();String prefix="_"+"x".repeat(200);
        f.attributes().put(prefix+"A",0).put(prefix+"B",0);
        var report=PrimitiveAttributesTest.validate(f).report();
        assertEquals(2,report.diagnostics().size());assertTrue(report.truncated());
        assertEquals(ValidationReport.Outcome.FAIL,report.outcome());
        var reversed=new SemanticFixtures();reversed.attributes().put(prefix+"B",0).put(prefix+"A",0);
        assertEquals(report,PrimitiveAttributesTest.validate(reversed).report());
    }
    @Test void pathOrMessageShorteningAlwaysReportsInformationLoss() {
        var path=new ValidationReport.Collector();path.error("E","/"+"x".repeat(160),"message");
        assertTrue(path.report().truncated());
        var message=new ValidationReport.Collector();message.error("E","/","m".repeat(241));
        assertTrue(message.report().truncated());
        var exact=new ValidationReport.Collector();exact.error("E","x".repeat(160),"m".repeat(240));
        assertFalse(exact.report().truncated());
    }
    @Test void sharedPrefixFloodRemainsBoundedAndTruthfullyTruncated() throws Exception {
        var f=new SemanticFixtures();for(int i=0;i<100;i++)f.attributes().put("_"+"x".repeat(200)+i,0);
        var r=PrimitiveAttributesTest.validate(f);assertEquals(64,r.report().diagnostics().size());
        assertTrue(r.report().truncated());assertTrue(r.snapshot().isEmpty());
    }
    @Test void identicalFullDiagnosticsAreDeduplicated() {
        var log=new ValidationReport.Collector();log.error("E","/same","message");log.error("E","/same","message");
        assertEquals(1,log.report().diagnostics().size());assertFalse(log.report().truncated());
    }
    @Test void maximumAdmittedEscapedNameRemainsDistinctAndOverBoundaryFailsClosed() throws Exception {
        var f=new SemanticFixtures();String prefix="_"+"/".repeat(4094);
        f.attributes().put(prefix+"A",0).put(prefix+"B",0);
        var r=PrimitiveAttributesTest.validate(f);assertEquals(2,r.report().diagnostics().size());assertTrue(r.report().truncated());
        var log=new ValidationReport.Collector();log.warning("W","x".repeat(HandToolProfile.MAX_DIAGNOSTIC_IDENTITY+1),"message");
        assertEquals(ValidationReport.Outcome.FAIL,log.report().outcome());assertTrue(log.report().truncated());
        assertEquals("DIAGNOSTIC_IDENTITY_LIMIT",log.report().diagnostics().get(0).code());
    }
    @ParameterizedTest
    @CsvSource({"3999,PASS", "4000,PASS", "4001,PASS_WITH_WARNINGS",
            "10000,PASS_WITH_WARNINGS", "10001,FAIL"})
    void uniqueTriangleBoundaries(long count, String outcome) {
        var budget = new HandToolProfile.GeometryBudget();
        budget.addUnique(count, 3);
        assertEquals(outcome, budget.report().outcome().name());
        assertEquals(count, budget.uniqueTriangles());
        assertEquals(0, budget.expandedTriangles());
    }

    @ParameterizedTest
    @CsvSource({"3999,PASS", "4000,PASS", "4001,PASS_WITH_WARNINGS",
            "10000,PASS_WITH_WARNINGS", "10001,FAIL"})
    void expandedTriangleBoundaries(long count, String outcome) {
        var budget = new HandToolProfile.GeometryBudget();
        budget.addExpanded(count, 3, 1);
        assertEquals(outcome, budget.report().outcome().name());
    }

    @ParameterizedTest
    @CsvSource({"30000,PASS", "30001,FAIL"})
    void bothVertexDomainsHaveIndependentHardLimits(long count, String outcome) {
        var unique = new HandToolProfile.GeometryBudget();
        unique.addUnique(1, count);
        assertEquals(outcome, unique.report().outcome().name());
        var expanded = new HandToolProfile.GeometryBudget();
        expanded.addExpanded(1, count, 1);
        assertEquals(outcome, expanded.report().outcome().name());
    }

    @Test
    void instancingFailureDoesNotEraseUniqueWarning() {
        var budget = new HandToolProfile.GeometryBudget();
        budget.addUnique(6000, 18000);
        budget.addExpanded(6000, 18000, 2);
        assertEquals(6000, budget.uniqueTriangles());
        assertEquals(12000, budget.expandedTriangles());
        assertEquals(36000, budget.expandedVertices());
        assertEquals(ValidationReport.Outcome.FAIL, budget.report().outcome());
        assertTrue(budget.report().diagnostics().stream().anyMatch(d ->
                d.code().equals("UNIQUE_TRIANGLE_WARNING")));
        assertTrue(budget.report().diagnostics().stream().anyMatch(d ->
                d.code().equals("EXPANDED_TRIANGLE_LIMIT")));
    }

    @Test
    void expandedWarningAndBothWarningsStayObservable() {
        var budget = new HandToolProfile.GeometryBudget();
        budget.addUnique(3000, 3);
        budget.addExpanded(3000, 3, 2);
        assertEquals(3000, budget.uniqueTriangles());
        assertEquals(6000, budget.expandedTriangles());
        assertEquals(List.of("EXPANDED_TRIANGLE_WARNING"),
                budget.report().diagnostics().stream().map(ValidationReport.Diagnostic::code).toList());
        budget.addUnique(1001, 3);
        assertEquals(2, budget.report().diagnostics().size());
    }

    @Test
    void sharedAccessorCountsPerPrimitiveAndUnreachableAddsOnlyUnique() {
        var budget = new HandToolProfile.GeometryBudget();
        budget.addUnique(1, 3);
        budget.addUnique(1, 3); // Another primitive sharing the POSITION accessor.
        budget.addExpanded(1, 3, 1); // Only the first primitive's mesh is reachable.
        assertEquals(6, budget.uniqueVertices());
        assertEquals(3, budget.expandedVertices());
    }

    @Test
    void multiplicationAndAdditionOverflowFailWithoutWrappedCounters() {
        var product = new HandToolProfile.GeometryBudget();
        product.addExpanded(Long.MAX_VALUE, 3, 2);
        assertEquals(ValidationReport.Outcome.FAIL, product.report().outcome());
        assertEquals(0, product.expandedTriangles());
        var sum = new HandToolProfile.GeometryBudget();
        sum.addUnique(Long.MAX_VALUE, 3);
        sum.addUnique(1, 3);
        assertEquals(ValidationReport.Outcome.FAIL, sum.report().outcome());
        assertEquals(Long.MAX_VALUE, sum.uniqueTriangles());
    }

    @Test
    void diagnosticTruncationCannotHideErrorAndResultsAreImmutable() {
        var log = new ValidationReport.Collector();
        for (int i = 0; i < HandToolProfile.MAX_DIAGNOSTICS + 10; i++) {
            log.warning("WARNING", "/nodes/" + i, "warning");
        }
        log.error("ERROR", "/nodes/0", "error");
        var report = log.report();
        assertTrue(report.truncated());
        assertEquals(ValidationReport.Outcome.FAIL, report.outcome());
        assertTrue(report.diagnostics().size() <= HandToolProfile.MAX_DIAGNOSTICS);
        assertThrows(UnsupportedOperationException.class, report.diagnostics()::clear);
        log.error("LATER", "/", "later");
        assertFalse(report.diagnostics().stream().anyMatch(d -> d.code().equals("LATER")));
    }

    @Test
    void diagnosticOrderAndTextBoundsAreDeterministic() {
        var first = new ValidationReport.Collector();
        first.warning("B", "/z", "later"); first.warning("A", "/a", "earlier");
        var second = new ValidationReport.Collector();
        second.warning("A", "/a", "earlier"); second.warning("B", "/z", "later");
        assertEquals(first.report(), second.report());
        first.error("E", "x".repeat(1000), "y".repeat(1000));
        assertTrue(first.report().diagnostics().stream().allMatch(d ->
                d.path().length() <= HandToolProfile.MAX_DIAGNOSTIC_PATH
                && d.message().length() <= HandToolProfile.MAX_DIAGNOSTIC_MESSAGE));
    }
}
