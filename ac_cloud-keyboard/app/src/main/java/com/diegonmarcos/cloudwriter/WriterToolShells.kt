package com.diegonmarcos.cloudwriter

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/**
 * #1698 the keyboard hosts cloud-writer's Tools pages too (Writer tab > Tools): the SAME five shells as ac_cloud-writer
 * (same class names, which libs:writer-ui WriterSurface opens by name), drawing the SAME libs:writer-ui pages.
 * #1652 THE ACTIVITY SHELLS of the five tool pages. The pages themselves (TextEnhancePage ...) are libs:writer-ui;
 * the shells live in the app because the status-bar inset is the HOST's to apply (fleet rule: shared pages never
 * handle the top row or the top inset), and the manifest names these classes.
 */
private fun appChrome(): Modifier = Modifier.statusBarsPadding()

class TextEnhanceActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = TextEnhancePage(this)
    override fun hostModifier(): Modifier = appChrome()
}

class TranslationActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = TranslationPage(this)
    override fun hostModifier(): Modifier = appChrome()
}

class GrammarCheckActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = GrammarCheckPage(this)
    override fun hostModifier(): Modifier = appChrome()
}

class AiRoutingActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = AiRoutingPage(this)
    override fun hostModifier(): Modifier = appChrome()
}

class RoutesActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = RoutesPage(this)
    override fun hostModifier(): Modifier = appChrome()
}
