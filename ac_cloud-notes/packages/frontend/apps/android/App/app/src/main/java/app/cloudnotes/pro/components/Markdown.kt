package app.cloudnotes.pro.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.cloudnotes.pro.theme.CloudNotesTheme
import app.cloudnotes.pro.theme.ThemeMode
import com.diegonmarcos.cloudlib.wysiwyg.TextFormat
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygColors
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygView

/**
 * #1484 Markdown view on the shared WYSIWYG engine (libs:wysiwyg, the same engine Cloud-Drive's Editor uses): the parsing,
 * the block layout and the inline marks are the lib's; only the Cloud Notes colours are this app's.
 */
@Composable
fun Markdown(
    modifier: Modifier = Modifier,
    markdown: String,
) {
    val c = CloudNotesTheme.colors
    WysiwygView(
        source = markdown,
        format = TextFormat.Markdown,
        modifier = modifier,
        colors = WysiwygColors(
            content = c.textPrimary,
            link = c.textEmphasis,
            codeBackground = c.backgroundCodeBlock,
            rule = c.divider,
            quoteBar = c.divider,
        ),
    )
}

@Preview
@Composable
fun MarkdownPreview() {
    CloudNotesTheme(mode = ThemeMode.Dark) {
        Markdown(
            markdown = """
# Heading one

## Heading two

**bold**, *italic*, `inline code` and a [link](https://example.org)

---

> a quote

- first
- second

1. one
2. two

```
fun main() { println("hello") }
```
            """.trimIndent(),
        )
    }
}
