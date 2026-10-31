package com.diegonmarcos.cloudkeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Debug builds only: a text field that has the focus and the keyboard from the start, for the CI screenshot job. */
class ShotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var text by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val platformField = intent.getBooleanExtra("edittext", false)
            LaunchedEffect(Unit) { if (!platformField) { focus.requestFocus(); keyboard?.show() } }
            Column(Modifier.padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp)) {
                BasicText("Cloud Keyboard", style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
                // --ez edittext true: a platform EditText, which draws the SuggestionSpan underline Grammar Check's
                // Highlight mode sets (a Compose text field keeps plain text only). The highlight-popup shots use it.
                if (platformField) {
                    androidx.compose.ui.viewinterop.AndroidView({ c ->
                        android.widget.EditText(c).apply {
                            textSize = 20f
                            requestFocus()
                            post { c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(this, 0) }
                        }
                    }, Modifier.fillMaxWidth())
                } else BasicTextField(text, { text = it }, Modifier.focusRequester(focus))
            }
        }
    }
}
