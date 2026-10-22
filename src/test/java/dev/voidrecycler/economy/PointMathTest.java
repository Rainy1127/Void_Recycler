package dev.voidrecycler.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure-logic tests: no Minecraft classes are loaded. */
class PointMathTest {
    @Test
    void satAddClampsInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, PointMath.satAdd(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, PointMath.satAdd(Long.MAX_VALUE - 5L, 100L));
        assertEquals(Long.MIN_VALUE, PointMath.satAdd(Long.MIN_VALUE, -1L));
        assertEquals(7L, PointMath.satAdd(3L, 4L));
        assertEquals(-1L, PointMath.satAdd(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    void satMulClampsInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, PointMath.satMul(Long.MAX_VALUE, 2L));
        assertEquals(Long.MIN_VALUE, PointMath.satMul(Long.MAX_VALUE, -2L));
        assertEquals(Long.MAX_VALUE, PointMath.satMul(Long.MIN_VALUE, -1L));
        assertEquals(12L, PointMath.satMul(3L, 4L));
        assertEquals(0L, PointMath.satMul(0L, Long.MAX_VALUE));
        assertEquals(5_000_000_000L, PointMath.satMul(5_000L, 1_000_000L));
    }

    @Test
    void formatPointsUsesUsGrouping() {
        assertEquals("0", PointMath.formatPoints(0L));
        assertEquals("1,500,000", PointMath.formatPoints(1_500_000L));
        assertEquals("9,223,372,036,854,775,807", PointMath.formatPoints(Long.MAX_VALUE));
    }

    @Test
    void formatMilliShowsOnlyNeededDecimals() {
        assertEquals("1.5", PointMath.formatMilli(1_500L));
        assertEquals("2", PointMath.formatMilli(2_000L));
        assertEquals("123.456", PointMath.formatMilli(123_456L));
        assertEquals("0.5", PointMath.formatMilli(500L));
        assertEquals("1,000.25", PointMath.formatMilli(1_000_250L));
    }

    @Test
    void formatCompactAbbreviates() {
        assertEquals("999", PointMath.formatCompact(999L));
        assertEquals("1K", PointMath.formatCompact(1_000L));
        assertEquals("1.5K", PointMath.formatCompact(1_500L));
        assertEquals("2.3M", PointMath.formatCompact(2_300_000L));
        assertEquals("150K", PointMath.formatCompact(150_000L));
    }

    @Test
    void efficiencyIsCappedAndNeverNegative() {
        assertEquals(500, PointMath.effectiveEfficiencyMilli(500, 100, 0));
        assertEquals(700, PointMath.effectiveEfficiencyMilli(500, 100, 2));
        assertEquals(900, PointMath.effectiveEfficiencyMilli(500, 100, 4));
        assertEquals(1000, PointMath.effectiveEfficiencyMilli(900, 500, 4));
        assertEquals(0, PointMath.effectiveEfficiencyMilli(0, 100, -3));
    }

    @Test
    void extractCapScalesWithModulesAndRespectsHardCap() {
        assertEquals(64, PointMath.effectiveExtractCap(64, 0, 65_536));
        assertEquals(320, PointMath.effectiveExtractCap(64, 4, 65_536));
        assertEquals(65_536, PointMath.effectiveExtractCap(4_096, 40, 65_536));
        assertEquals(1, PointMath.effectiveExtractCap(0, 2, 65_536));
    }
}
