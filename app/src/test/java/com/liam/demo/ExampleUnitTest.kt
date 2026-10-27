package com.liam.demo

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `domain import nothing from Android`() {
        Konsist.scopeFromModule("domain")
            .imports
            .assertFalse { it.name.startsWith("android.") || it.name.startsWith("androidx.") }
    }

}
