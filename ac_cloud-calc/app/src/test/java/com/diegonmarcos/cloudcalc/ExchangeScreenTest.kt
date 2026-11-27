package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.ExchangeMode
import com.diegonmarcos.cloudcalc.ui.ExchangeTags
import com.diegonmarcos.cloudcalc.ui.LocalCalcApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** #1945 the Exchange page composed against a fake engine: the layout, the picker, remove, typing, offline, persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExchangeScreenTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private class FakeEngine : CalcApi {
        val perEur = mapOf("INR" to "100 INR", "USD" to "1.08 USD", "EUR" to "1 EUR", "GBP" to "0.84 GBP", "BRL" to "6.2 BRL", "JPY" to "160 JPY")
        @Volatile var fetchOk = true
        override fun info() = "{}"
        override fun eval(expr: String, options: String): String {
            val r = perEur[expr.substringAfterLast(" ")] ?: return """{"ok":false,"result":"","messages":[]}"""
            return JSONObject().put("ok", true).put("result", r).put("messages", JSONArray()).toString()
        }
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = "{}"
        override fun complete(prefix: String, max: Int) = "[]"
        override fun items(kind: String, category: String, max: Int) =
            JSONArray(perEur.keys.map { JSONObject().put("name", it).put("title", "$it title").put("kind", "unit").put("category", category) }).toString()
        override fun ratesInfo() = """{"time":1600000000}"""
        override fun fetchRates() = if (fetchOk) """{"ok":true,"time":1600000000}""" else """{"ok":false,"error":"offline"}"""
    }

    private val engine = FakeEngine()
    private val mode get() = Declarations.mode("exchange")!!
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences(Exchange.PREFS, Context.MODE_PRIVATE)

    @Before fun fresh() {
        Locale.setDefault(Locale.US)
        prefs.edit().clear().commit()
        Fx.lastAttemptMs = 0L
    }

    private fun launch() {
        compose.setContent { CalcTheme { CompositionLocalProvider(LocalCalcApi provides engine) { ExchangeMode(mode) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ExchangeTags.targetRate("USD"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(ExchangeTags.targetRate("USD"), useUnmergedTree = true).assertTextContains("0.0108", substring = true) }.isSuccess }
    }

    @Test fun `the layout is the source card, one card per target with its unit rate, the add action and the footer`() {
        launch()
        compose.onNodeWithTag(ExchangeTags.SOURCE_CARD).assertExists()
        compose.onNodeWithText("You convert").assertExists()
        compose.onNodeWithTag(ExchangeTags.SOURCE_CODE).assertTextContains("INR", substring = true)
        compose.onNodeWithTag(ExchangeTags.AMOUNT).assertTextContains("858.02")
        val targets = listOf("USD", "EUR", "GBP", "BRL")
        targets.forEach { compose.onNodeWithTag(ExchangeTags.target(it)).assertExists() }
        compose.onNodeWithTag(ExchangeTags.targetRate("USD"), useUnmergedTree = true).assertTextContains("1 INR → 0.0108 USD")
        compose.onNodeWithTag(ExchangeTags.targetRate("EUR"), useUnmergedTree = true).assertTextContains("1 INR → 0.0100 EUR")
        compose.onNodeWithTag(ExchangeTags.targetRate("GBP"), useUnmergedTree = true).assertTextContains("1 INR → 0.0084 GBP")
        compose.onNodeWithTag(ExchangeTags.targetRate("BRL"), useUnmergedTree = true).assertTextContains("1 INR → 0.0620 BRL")
        compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).assertTextContains("9.27")
        compose.onNodeWithTag(ExchangeTags.targetAmount("EUR"), useUnmergedTree = true).assertTextContains("8.58")
        compose.onNodeWithTag(ExchangeTags.ADD).assertTextContains("Add another currency")
        compose.onNodeWithTag(ExchangeTags.REFRESH).assertExists()
        compose.onNodeWithTag(ExchangeTags.UPDATED).assertTextContains("Last updated:", substring = true)
        compose.onNodeWithTag(ExchangeTags.MID_MARKET).assertTextContains("Mid-market rates")
        compose.onNodeWithTag(ExchangeTags.INFO).performClick()
        compose.onNodeWithTag(ExchangeTags.INFO_BODY).assertTextContains(Exchange.spec(mode).sourceName, substring = true)
    }

    @Test fun `typing an amount converts every card`() {
        launch()
        compose.onNodeWithTag(ExchangeTags.AMOUNT).performTextReplacement("100")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).assertTextContains("1.08") }.isSuccess }
        compose.onNodeWithTag(ExchangeTags.targetAmount("GBP"), useUnmergedTree = true).assertTextContains("0.84")
        assertEquals("100", prefs.getString("exchange_amount", null))
    }

    private fun waitText(tag: String, text: String) =
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertTextContains(text) }.isSuccess }

    @Test fun `typing into the second box makes it the input and recomputes the others`() {
        launch()
        compose.onNodeWithTag(ExchangeTags.AMOUNT).assertIsSelected()
        compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).performTextReplacement("100")
        // 100 USD -> INR 10,000.00 / EUR 92.59 / GBP 77.78 / BRL 574.07 through the EUR base
        waitText(ExchangeTags.AMOUNT, "9,259.26")
        waitText(ExchangeTags.targetAmount("EUR"), "92.59")
        waitText(ExchangeTags.targetAmount("GBP"), "77.78")
        waitText(ExchangeTags.targetAmount("BRL"), "574.07")
        compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).assertTextContains("100")
        compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag(ExchangeTags.AMOUNT).assertIsNotSelected()
        // the unit-rate lines now read from USD
        waitText(ExchangeTags.targetRate("EUR"), "1 USD → 0.9259 EUR")
        waitText(ExchangeTags.targetRate("USD"), "1 USD → 92.5926 INR")
        assertEquals(Exchange.State("INR", "100", listOf("USD", "EUR", "GBP", "BRL"), 1), Exchange.Store(prefs).load(Exchange.defaults(mode)))
    }

    @Test fun `typing into the third box then back in the first keeps the values consistent`() {
        launch()
        compose.onNodeWithTag(ExchangeTags.targetAmount("EUR"), useUnmergedTree = true).performTextReplacement("50")
        waitText(ExchangeTags.AMOUNT, "5,000.00")
        waitText(ExchangeTags.targetAmount("USD"), "54.00")
        waitText(ExchangeTags.targetAmount("GBP"), "42.00")
        compose.onNodeWithTag(ExchangeTags.targetAmount("EUR"), useUnmergedTree = true).assertIsSelected()
        // the first box is the input again: typing 5000 INR gives back 50.00 EUR and the same others
        compose.onNodeWithTag(ExchangeTags.AMOUNT).performTextReplacement("5000")
        waitText(ExchangeTags.targetAmount("EUR"), "50.00")
        compose.onNodeWithTag(ExchangeTags.targetAmount("USD"), useUnmergedTree = true).assertTextContains("54.00")
        compose.onNodeWithTag(ExchangeTags.AMOUNT).assertIsSelected()
    }

    @Test fun `a currency is added through the searchable picker and persists`() {
        launch()
        compose.onNodeWithTag(ExchangeTags.ADD).performClick()
        compose.onNodeWithTag(ExchangeTags.PICKER_SEARCH).performTextReplacement("jp")
        compose.onNodeWithTag(ExchangeTags.pickerItem("JPY")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ExchangeTags.target("JPY")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(ExchangeTags.targetRate("JPY"), useUnmergedTree = true).assertTextContains("1 INR → 1.6000 JPY") }.isSuccess }
        assertTrue(prefs.getString("exchange_targets", "")!!.contains("JPY"))
    }

    @Test fun `a long press removes a target and the removal persists`() {
        launch()
        // the amount is a text field now (a long press there selects text): the menu opens from the currency chip
        compose.onNodeWithTag(ExchangeTags.targetCode("EUR"), useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag(ExchangeTags.remove("EUR")).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(ExchangeTags.target("EUR")).fetchSemanticsNodes().size)
        assertEquals(Exchange.State("INR", "858.02", listOf("USD", "GBP", "BRL")), Exchange.Store(prefs).load(Exchange.defaults(mode)))
    }

    @Test fun `a failed refresh shows the offline state with the cached time`() {
        engine.fetchOk = false
        launch()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(ExchangeTags.UPDATED).assertTextContains("Offline", substring = true) }.isSuccess }
        compose.onNodeWithTag(ExchangeTags.UPDATED).assertTextContains("Last updated:", substring = true)
        compose.onNodeWithTag(ExchangeTags.targetRate("USD"), useUnmergedTree = true).assertTextContains("0.0108", substring = true)
    }
}
