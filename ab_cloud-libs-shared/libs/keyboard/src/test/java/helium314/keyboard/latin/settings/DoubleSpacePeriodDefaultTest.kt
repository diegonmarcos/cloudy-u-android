package helium314.keyboard.latin.settings

import org.junit.Assert.assertFalse
import org.junit.Test

/** #1912: "double space inserts a period" ships OFF; the user can still turn it on in Text correction. */
class DoubleSpacePeriodDefaultTest {
    @Test
    fun `double space period is off by default`() {
        assertFalse(
            "Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD must be false (#1912)",
            Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD
        )
    }
}
