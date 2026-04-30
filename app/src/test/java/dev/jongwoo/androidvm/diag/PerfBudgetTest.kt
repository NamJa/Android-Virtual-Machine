package dev.jongwoo.androidvm.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfBudgetTest {
    @Test
    fun samplesUnderBudgetPass() {
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 700, fpsAvg = 30, fdCount = 200, auditAppendsPerMinute = 100),
        )
        assertTrue(verdict.passed)
        assertTrue(verdict.formatLine().contains("passed=true"))
        assertTrue(verdict.formatLine().contains("rss_mb=700"))
    }

    @Test
    fun rssOverflowFlagsRssOnly() {
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 1500, fpsAvg = 30, fdCount = 200, auditAppendsPerMinute = 100),
        )
        assertFalse(verdict.passed)
        assertFalse(verdict.rssOk)
        assertTrue(verdict.fpsOk)
        assertTrue(verdict.fdOk)
        assertTrue(verdict.auditOk)
    }

    @Test
    fun belowFpsFlagsFpsOnly() {
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 100, fpsAvg = 12, fdCount = 200, auditAppendsPerMinute = 100),
        )
        assertFalse(verdict.fpsOk)
        assertFalse(verdict.passed)
    }

    @Test
    fun fdOverflowFlagsFdOnly() {
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 100, fpsAvg = 30, fdCount = 1024, auditAppendsPerMinute = 100),
        )
        assertFalse(verdict.fdOk)
        assertFalse(verdict.passed)
    }

    @Test
    fun auditFloodFlagsAuditOnly() {
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 100, fpsAvg = 30, fdCount = 200, auditAppendsPerMinute = 6_000),
        )
        assertFalse(verdict.auditOk)
        assertFalse(verdict.passed)
    }

    @Test
    fun customBudgetIsHonoured() {
        val budget = PerfBudget(rssMaxMib = 256)
        val verdict = PerfBudgetEvaluator.evaluate(
            PerfSample(rssMib = 300, fpsAvg = 30, fdCount = 100, auditAppendsPerMinute = 50),
            budget = budget,
        )
        assertFalse(verdict.passed)
        assertEquals(256, verdict.budget.rssMaxMib)
    }
}
