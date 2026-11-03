package com.hotomeasure.app.data

import java.util.Locale
import kotlin.math.roundToLong

object Units {
    private const val MM_PER_IN = 25.4
    private const val MM_PER_FT = 304.8

    fun isImperial(u: DisplayUnit) = u == DisplayUnit.FT_IN || u == DisplayUnit.FT || u == DisplayUnit.IN

    fun length(mm: Int, s: Settings): String = when (s.unit) {
        DisplayUnit.M -> "${fmt(mm / 1000.0, s.decimals.coerceAtLeast(1).coerceAtMost(3))} m"
        DisplayUnit.CM -> "${fmt(mm / 10.0, 1)} cm"
        DisplayUnit.MM -> "$mm mm"
        DisplayUnit.FT -> "${fmt(mm / MM_PER_FT, s.decimals)} ft"
        DisplayUnit.IN -> "${inches(mm / MM_PER_IN, s.inchFraction)}\""
        DisplayUnit.FT_IN -> feetInches(mm, s.inchFraction)
    }

    /** The same length in the "other" system, shown smaller under the main reading. */
    fun alternate(mm: Int, s: Settings): String =
        if (isImperial(s.unit)) length(mm, s.copy(unit = DisplayUnit.M, decimals = 3))
        else length(mm, s.copy(unit = DisplayUnit.FT_IN))

    fun area(mmA: Int, mmB: Int, s: Settings): String {
        val m2 = mmA / 1000.0 * (mmB / 1000.0)
        return if (isImperial(s.unit)) "${fmt(m2 * 10.7639104, s.decimals)} ft²" else "${fmt(m2, s.decimals)} m²"
    }

    fun volume(mmA: Int, mmB: Int, mmC: Int, s: Settings): String {
        val m3 = mmA / 1000.0 * (mmB / 1000.0) * (mmC / 1000.0)
        return if (isImperial(s.unit)) "${fmt(m3 * 35.3146667, s.decimals)} ft³" else "${fmt(m3, s.decimals)} m³"
    }

    fun feetInches(mm: Int, fraction: Int): String {
        val totalIn = mm / MM_PER_IN
        val units = (totalIn * fraction).roundToLong() // whole number of 1/fraction inches
        val perFoot = 12L * fraction
        val feet = units / perFoot
        val rest = units % perFoot
        val inchText = inchesFromUnits(rest, fraction)
        return if (feet == 0L) "$inchText\"" else "$feet' $inchText\""
    }

    fun inches(inches: Double, fraction: Int): String = inchesFromUnits((inches * fraction).roundToLong(), fraction)

    private fun inchesFromUnits(units: Long, fraction: Int): String {
        val whole = units / fraction
        var num = units % fraction
        var den = fraction.toLong()
        if (num == 0L) return "$whole"
        while (num % 2 == 0L && den % 2 == 0L) { num /= 2; den /= 2 }
        return if (whole == 0L) "$num/$den" else "$whole $num/$den"
    }

    private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)
}
