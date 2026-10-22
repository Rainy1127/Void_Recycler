package dev.voidrecycler.economy;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Pure (Minecraft-free) number helpers: saturating 64-bit maths and the number formats used by the GUI.
 * Kept separate from {@link Economy} so it can be unit tested without a game environment.
 */
public final class PointMath {
    private PointMath() {}

    public static long satAdd(long a, long b) {
        long r = a + b;
        // overflow iff both operands have the same sign and the result's sign differs
        return (((a ^ r) & (b ^ r)) < 0L) ? (a < 0L ? Long.MIN_VALUE : Long.MAX_VALUE) : r;
    }

    public static long satMul(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        if ((hi == 0L && lo >= 0L) || (hi == -1L && lo < 0L)) {
            return lo;
        }
        return ((a < 0L) ^ (b < 0L)) ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    /** 1500000 -> "1,500,000" (always US grouping so the GUI looks identical in every locale). */
    public static String formatPoints(long points) {
        return NumberFormat.getIntegerInstance(Locale.US).format(points);
    }

    /** Formats a value given in thousandths: 1500 -> "1.5", 2000 -> "2", 123456 -> "123.456". */
    public static String formatMilli(long milli) {
        long whole = milli / 1000L;
        long frac = Math.abs(milli % 1000L);
        if (frac == 0L) {
            return formatPoints(whole);
        }
        String fraction = String.format(Locale.ROOT, "%03d", frac).replaceAll("0+$", "");
        return formatPoints(whole) + "." + fraction;
    }

    /** Compact form for narrow spaces: 1_500 -> "1.5K", 2_300_000 -> "2.3M". Below 1000 the plain number. */
    public static String formatCompact(long value) {
        long abs = Math.abs(value);
        if (abs < 1_000L) {
            return Long.toString(value);
        }
        String[] suffix = {"K", "M", "B", "T", "Q"};
        double scaled = value;
        int i = -1;
        while (Math.abs(scaled) >= 1_000.0D && i < suffix.length - 1) {
            scaled /= 1_000.0D;
            i++;
        }
        String number = Math.abs(scaled) >= 100.0D
                ? String.format(Locale.ROOT, "%.0f", scaled)
                : String.format(Locale.ROOT, "%.1f", scaled).replaceAll("\\.0$", "");
        return number + suffix[i];
    }

    /**
     * Efficiency in thousandths after upgrades: {@code base + count * bonus}, clamped to 0..1000.
     * All inputs are thousandths, so 500 = 50%.
     */
    public static int effectiveEfficiencyMilli(int baseMilli, int bonusPerUpgradeMilli, int upgradeCount) {
        long total = (long) baseMilli + (long) bonusPerUpgradeMilli * Math.max(0, upgradeCount);
        return (int) Math.max(0L, Math.min(1000L, total));
    }

    /** Pipe extract cap after throughput modules: {@code base * (1 + modules)}, saturating at {@code hardCap}. */
    public static int effectiveExtractCap(int base, int modules, int hardCap) {
        long total = (long) base * (1L + Math.max(0, modules));
        return (int) Math.max(1L, Math.min(hardCap, total));
    }
}
