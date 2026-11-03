package com.hotomeasure.app

import com.hotomeasure.app.data.DisplayUnit
import com.hotomeasure.app.data.Settings
import com.hotomeasure.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    private val ftIn = Settings(unit = DisplayUnit.FT_IN, inchFraction = 16)

    @Test
    fun feetAndInches() {
        assertEquals("4' 9 5/8\"", Units.length(1463, ftIn)) // 57.598"
        assertEquals("1' 0\"", Units.length(305, ftIn)) // 12.008" -> 1' 0"
        assertEquals("1/2\"", Units.length(13, ftIn)) // 0.512"
        assertEquals("3' 0\"", Units.length(914, ftIn.copy(inchFraction = 8))) // 35.98" rounds up to 36"
    }

    @Test
    fun metric() {
        assertEquals("1.46 m", Units.length(1463, Settings(unit = DisplayUnit.M, decimals = 2)))
        assertEquals("1.463 m", Units.length(1463, Settings(unit = DisplayUnit.M, decimals = 3)))
        assertEquals("146.3 cm", Units.length(1463, Settings(unit = DisplayUnit.CM)))
        assertEquals("1463 mm", Units.length(1463, Settings(unit = DisplayUnit.MM)))
    }

    @Test
    fun areaAndVolume() {
        assertEquals("6.00 m²", Units.area(2000, 3000, Settings(unit = DisplayUnit.M)))
        assertEquals("100.00 ft²", Units.area(3048, 3048, ftIn))
        assertEquals("1000.00 ft³", Units.volume(3048, 3048, 3048, ftIn))
    }
}
