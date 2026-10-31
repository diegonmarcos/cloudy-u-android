// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * #1077: the order of the keyboard's own entries at the top of Configs, declared once. The screen draws them in
 * this order and a test pins it, so a page cannot drift to another position by an edit elsewhere.
 * Toolbar is first, Autocomplete sits right after Clipboard. #1384: Draft Answer sits right after Text Enhancement.
 */
enum class ConfigsEntry { TOOLBAR, CLIPBOARD, AUTOCOMPLETE, EMOJI, TRANSLATION, TEXT_ENHANCE, DRAFT_ANSWER, GRAMMAR_CHECK, VOICE_TRANSCRIPT, AI_ROUTING, ANDROID_SETTINGS, UPDATE }
