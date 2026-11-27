package com.datakhaos.workflow.executor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreconditionEvaluatorTest {

    @Test
    void blankExpressionAlwaysRuns() {
        assertTrue(PreconditionEvaluator.evaluate(null, Map.of()));
        assertTrue(PreconditionEvaluator.evaluate("   ", Map.of()));
    }

    @Test
    void comparesResolvedParameters() {
        Map<String, Object> params = Map.of("bizDate", "2026-11-27", "hour", 9, "env", "prod");
        assertTrue(PreconditionEvaluator.evaluate("${bizDate} >= '2026-01-01'", params));
        assertFalse(PreconditionEvaluator.evaluate("${bizDate} < '2026-01-01'", params));
        assertTrue(PreconditionEvaluator.evaluate("${hour} == 9", params));
        assertTrue(PreconditionEvaluator.evaluate("${env} == 'prod'", params));
        assertTrue(PreconditionEvaluator.evaluate("${env} != 'test'", params));
    }

    @Test
    void supportsScriptStyleIfBlock() {
        Map<String, Object> params = Map.of("bizDate", "2026-11-27");
        assertTrue(PreconditionEvaluator.evaluate(
                "if [ ${bizDate} >= '2026-01-01' ] then exit 0; else exit 1;", params));
        assertFalse(PreconditionEvaluator.evaluate(
                "if [ ${bizDate} < '2026-01-01' ] then exit 0; else exit 1;", params));
    }

    @Test
    void combinesAndOrLeftToRight() {
        Map<String, Object> params = Map.of("env", "prod", "hour", 10);
        assertTrue(PreconditionEvaluator.evaluate("${env} == 'prod' && ${hour} > 8", params));
        assertFalse(PreconditionEvaluator.evaluate("${env} == 'prod' && ${hour} > 12", params));
        assertTrue(PreconditionEvaluator.evaluate("${env} == 'test' || ${hour} > 8", params));
        assertFalse(PreconditionEvaluator.evaluate("${env} == 'test' || ${hour} > 12", params));
    }

    @Test
    void keepsLogicalOperatorsInsideQuotes() {
        Map<String, Object> params = Map.of("label", "a && b");
        assertTrue(PreconditionEvaluator.evaluate("${label} == 'a && b'", params));
    }

    @Test
    void missingParameterBecomesEmptyString() {
        assertFalse(PreconditionEvaluator.evaluate("${missing} == 'x'", Map.of()));
        assertFalse(PreconditionEvaluator.evaluate("${missing}", Map.of()));
    }

    @Test
    void bareValueUsesTruthiness() {
        assertTrue(PreconditionEvaluator.evaluate("${flag}", Map.of("flag", "true")));
        assertFalse(PreconditionEvaluator.evaluate("${flag}", Map.of("flag", "false")));
        assertFalse(PreconditionEvaluator.evaluate("${count}", Map.of("count", 0)));
        assertTrue(PreconditionEvaluator.evaluate("${count}", Map.of("count", 0.5)));
    }

    @Test
    void rejectsOrderingComparisonOnNonNumericValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PreconditionEvaluator.evaluate("${env} > 'prod'", Map.of("env", "prod")));
    }

    @Test
    void rejectsEmptyAtom() {
        assertThrows(IllegalArgumentException.class,
                () -> PreconditionEvaluator.evaluate("${a} == 1 && ", Map.of("a", 1)));
    }
}
