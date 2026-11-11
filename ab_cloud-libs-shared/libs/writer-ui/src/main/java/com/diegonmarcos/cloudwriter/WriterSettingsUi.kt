package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentActivity
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwriter.ui.BlockGap
import com.diegonmarcos.cloudwriter.ui.CloudWriterTheme
import com.diegonmarcos.cloudwriter.ui.PageGutter
import com.diegonmarcos.cloudwriter.ui.SectionHeader
import com.diegonmarcos.cloudwriter.ui.WriterTheme
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher

/**
 * The shape of a cloud-writer configuration page, and the rows it is built from.
 *
 * WHAT THIS IS A COPY OF. cloud-keyboard draws its settings with Compose — `SearchSettingsScreen`
 * over `ListPreference`, `SwitchPreference`, `TextInputPreference`, `PreferenceCategory`. This file
 * is the same four widgets and the same page shape, in cloud-writer's own Material 3 components
 * over cloud-writer's own store. A row is a title, an optional explanation under it, and the
 * current value; tapping a list row opens a single-choice dialog, exactly as the keyboard's does.
 *
 * WHY NOT LITERALLY THE KEYBOARD'S COMPOSABLES. Two reasons and neither is taste. The first is the
 * task: importing them is the LINK that made task 209 — one screen over the other application's
 * store, nothing editable. The second is that they are not importable in any case; every one of
 * them resolves `helium314.keyboard.latin.R`, reads `SettingsActivity.settingsContainer` and writes
 * the keyboard's own device-protected preference file. Taking the widgets would have taken the
 * store with them.
 *
 * NOTHING IN THIS FILE, OR IN ANY PAGE BUILT ON IT, READS OR WRITES ANYTHING OUTSIDE THIS
 * APPLICATION. Every value goes through [WriterPrefs], which is one private SharedPreferences file
 * in cloud-writer's own data directory. There is no `createPackageContext`, no ContentProvider, no
 * `sharedUserId` and no Intent into the keyboard's settings anywhere in this application — that is
 * asserted by test-cloud-writer-settings-pages.sh, which is what makes "same" mean "same and mine".
 *
 * ── WHAT THE UI ENHANCEMENT CHANGED HERE ────────────────────────────────────────────────────
 *
 * These rows used to be TextViews inside LinearLayouts, built by addView against four hex literals
 * copy-pasted from MainActivity and a `PAD = 36` used as a RAW PIXEL count — so every margin on
 * every page was a different physical size on every phone, and roughly half the intended one on a
 * modern high-density Samsung. They are now Material 3 over the type scale and the colour roles,
 * in dp. No row's BEHAVIOUR moved: a list row still opens a single-choice dialog, a switch row is
 * still a whole-row target, and the preview row is still handed text composed by the same call the
 * run makes.
 */
abstract class WriterSettingsPage(val activity: ComponentActivity) : ContextWrapper(activity) {

    /** The page's own title, drawn as the large app-bar title — the theme is NoActionBar. */
    abstract fun pageTitle(): String

    /**
     * The page's rows.
     *
     * WAS `buildPage()`, AN IMPERATIVE FILL OF A LinearLayout. It is now a composable that
     * DESCRIBES the page, which is what removes [rebuild]: a page whose contents depend on a
     * setting no longer has to tear itself down and re-add every view, because reading the setting
     * during composition is what makes it recompose when the setting changes. The one page that
     * needed it — AI Model Routing, whose model list and price table belong to the provider — gets
     * that for free now.
     */
    @Composable
    abstract fun PageContent()

    /** Called each time the hosting activity resumes (and once on attach), as the activity's onResume was. */
    open fun onResume() {}

    /**
     * #1652 THE PAGE WITHOUT THE ACTIVITY'S CHROME: the scrolling rows and nothing else, so a host
     * that has its own navigation (the keyboard's Writer tab, the Tools page of [WriterSurface])
     * draws the page inside it. [WriterSettingsActivity] draws the very same body inside the
     * app's own shell, so the two can never differ.
     */
    @Composable
    fun Body(modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
        DisposableEffect(this) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onResume() }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            header()
            PageContent()
            // The last row of a settings page sat flush against the navigation bar
            // before; a page that ends exactly at the edge reads as a page that has
            // been cut off.
            Spacer(Modifier.height(BlockGap * 2))
        }
    }

    // ── the rows, one per keyboard preference widget ─────────────────────────

    /**
     * `PreferenceCategory` — a section heading, e.g. "Correcciones".
     *
     * Kept as a method on the activity so the four pages read the way they did, and so a page
     * cannot accidentally use a heading from somewhere else.
     */
    @Composable
    protected fun Category(text: String) {
        SectionHeader(text, Modifier.padding(horizontal = PageGutter))
    }

    /**
     * A group of rows inside one surface.
     *
     * NEW, AND THE REASON THE PAGES READ AS PAGES NOW. Every row used to be appended to one flat
     * column with nothing between them, so seven settings and three explanatory notes were one
     * undifferentiated wall of text. A card per group gives the eye somewhere to stop.
     */
    @Composable
    protected fun Group(content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
        }
    }

    /**
     * `TextInputPreference` — title, explanation, current text; a tap opens an editable dialog.
     *
     * The dialog's field is a real text field with the platform's own editing, as it was: this
     * application has never had a hand-rolled editor to inherit the keyboard's editing defects
     * from. See [com.diegonmarcos.cloudwriter.ui.WriterTextField].
     */
    @Composable
    protected fun TextRow(title: String, summary: String?, current: String, hint: String, onSet: (String) -> Unit) {
        var stored by remember(current) { mutableStateOf(current) }
        var open by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf(current) }

        com.diegonmarcos.cloudwriter.ui.SettingRow(
            title = title,
            summary = summary,
            value = stored.ifBlank { hint },
            onClick = { draft = stored; open = true },
        )
        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text(title) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        stored = draft.trim()
                        onSet(stored)
                        open = false
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }

    /**
     * `PromptPreview` — READ-ONLY text the page shows but does not let you edit, with a Copy
     * button.
     *
     * [text] must be handed in ALREADY COMPOSED by the same function the run calls. Re-assembling
     * it here would be a second copy of the composition rule, and the day the two disagree this
     * row is lying about what the application sends — which is the whole reason the row exists.
     *
     * IT RECOMPOSES ON ITS OWN NOW. The View version returned the TextView so the page could
     * re-point it by hand after every pick, and forgetting that call was a real, tested failure
     * mode — a preview that only updated on reopening is indistinguishable from a hardcoded one
     * for as long as the owner stays on the page. Here [text] is a parameter read during
     * composition, so a changed setting redraws it because there is no other thing it could do.
     */
    @Composable
    protected fun ReadOnlyRow(title: String, summary: String?, text: String) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { copyToClipboard(title, text) }) {
                    Text(stringResource(R.string.enhance_test_copy))
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(this, R.string.enhance_test_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * ONE HORIZONTALLY SCROLLING TABLE: a header row and ONE ROW PER MODEL, never two.
     *
     * Eight columns do not fit a phone and the answer is to scroll, not to fold. Every cell is
     * single-line without exception — one cell wrapping is enough to take a model onto a second
     * line, which is the layout this table exists to replace.
     *
     * THE SCROLLER WRAPS THE WHOLE TABLE rather than each row, so the columns cannot drift out
     * from under their headings however far it is dragged: there is one scroll position because
     * there is one scroller. Sideways belongs to the table and vertically belongs to the page,
     * which are perpendicular, so no gesture is contested.
     *
     * [widthsDp] is each column's longest value, not a share of the screen. Widening one costs
     * nothing but a little more sideways travel.
     *
     * THE SIGNATURE AND EVERY ONE OF THESE PROPERTIES ARE UNCHANGED BY THE UI WORK, DELIBERATELY.
     * This table carries tasks 214, 186/187, 217 and 219, and restyling is exactly the kind of
     * change that quietly undoes them: a wrapped cell brings 214 back, a "tidier" number format
     * brings 217 or 186 back. The cells are single-line by construction — maxLines 1 AND
     * TextOverflow.Clip — and the one scroller still wraps the whole body.
     */
    @Composable
    protected fun Table(headers: List<String>, rows: List<List<String>>, widthsDp: List<Int>, dimFrom: Int) {
        require(headers.size == widthsDp.size) { "a column without a width would draw at zero and vanish" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PageGutter, vertical = 8.dp),
        ) {
            Column {
                TableRow(headers, widthsDp, dimFrom = 0, header = true)
                rows.forEach { TableRow(it, widthsDp, dimFrom, header = false) }
            }
        }
    }

    @Composable
    private fun TableRow(cells: List<String>, widthsDp: List<Int>, dimFrom: Int, header: Boolean) {
        Row(Modifier.padding(vertical = 3.dp)) {
            cells.forEachIndexed { at, text ->
                Text(
                    text = text,
                    style = if (header) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (at >= dimFrom) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    // ONE LINE PER MODEL (task 214). Clip rather than an ellipsis: the column is
                    // already as wide as its longest value, so an ellipsis would only ever appear
                    // if a width were wrong, and a silently shortened model code is worse than a
                    // visibly cut one.
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.width(widthsDp[at].dp),
                )
            }
        }
    }

    /** A grey sentence on its own, for the notes that say what a row on this page does NOT do. */
    @Composable
    protected fun Note(text: String) {
        com.diegonmarcos.cloudwriter.ui.NoteText(text)
    }
}

/**
 * The activity shell of a configuration page: the app's own first row, bottom nav and back button
 * around [WriterSettingsPage.Body]. The page itself is built by [createPage] so the same page runs
 * inside any ComponentActivity (see [WriterSurface]).
 */
abstract class WriterSettingsActivity : AppCompatActivity() {

    protected abstract fun createPage(): WriterSettingsPage

    /** The host's window-inset treatment of the shell (cloud-writer's activities pass the status-bar padding); a shared page never handles the top inset. */
    protected open fun hostModifier(): Modifier = Modifier

    private lateinit var page: WriterSettingsPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        page = createPage()
        title = page.pageTitle()
        setContent {
            CloudWriterTheme {
                // #1164 the same shell as MainActivity's: the shared first row, bottom nav, page background and scroll-collapse.
                val dark = WriterTheme.isDark()
                val entries = WriterNav.decl.bottomSections().map { sec ->
                    navEntry(sec.id, sec.label, sec.icon)
                }
                NavBarTopFirstRowScaffold(
                    title = page.pageTitle(),
                    hierarchy = emptyList(),
                    onNavigate = {},
                    onAccount = rememberAccountLauncher(),
                    onConfigs = { openSection(WriterNav.SETTINGS) },
                    onAi = rememberSuperAppAiLauncher(),
                    modifier = Modifier.fillMaxSize().then(hostModifier()),
                    dark = dark,
                ) {
                    NavBarBottomHost(
                        entries = entries,
                        selectedId = WriterNav.TOOLS,
                        onSelect = { e -> if (e.id == WriterNav.TOOLS) finish() else openSection(e.id) },
                        dark = dark,
                    ) {
                        page.Body {
                            // TOP RIGHT, as before: the back button sits where the thumb already is. Every configuration
                            // page of this application is built on this one base, so all of them get it from this place.
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.page_back),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A bottom-nav item pressed on a tool page: the main screen at that section (the tool page closes). */
    private fun openSection(id: String) {
        startActivity(
            WriterSurface.mainIntent(this)
                .putExtra(WriterSurface.EXTRA_SECTION, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

