#!/usr/bin/env bash
# cloud-mail named signatures (#206): static + executed proof that the signature field genuinely
# accepts HTML, that plain-text signatures are untouched by it, that the ONE existing signature
# migrates to the default of the new set, and that nothing an owner stores can execute when rendered.
# Same shape as test-mail-compose-signature-order.sh, because this runner cannot build Kotlin.
#
#   N1  the editor edits the SOURCE, so typed HTML survives recomposition (the label stopped lying)
#   N2  plain vs HTML is told apart by ONE discriminator, the one the legacy split already used
#   N3  the pre-#206 single signature migrates to the default of the new set; a fresh install gets
#       NO signature rather than one blank one  -- RUN, not grepped
#   N4  the compose picker reaches the identity's signatures and rewrites the body through the SAME
#       path the "From" picker uses, so the two cannot drift
#   N5  the render path sanitises the signature: on the wire AND in the editor's own preview
#   N6  every label this feature added exists in all nine languages
#   N7  the HTML source editor: a toolbar that edits the SOURCE, highlighting, undo/redo, a sanitised
#       JavaScript-off preview with the derived plain text, and no Import HTML button any more
#   N8  the signatures travel: the mail_signatures store is declared, served by a registered handler,
#       and the transfer is keyed by ADDRESS
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
SIGNATURE="$DATA/account/StoredSignature.kt"
IDENTITY="$DATA/account/StoredIdentity.kt"
ACCOUNT="$DATA/account/StoredAccount.kt"
EDITOR="$UI/settings/SignatureEditor.kt"
TEXT="$UI/compose/ComposeText.kt"
VM="$UI/compose/ComposeViewModel.kt"
SCREEN="$UI/compose/ComposeScreen.kt"
TOOLS="$UI/settings/SignatureHtmlTools.kt"
# The full-screen editor (replaced the inline field): list, dialog + screen, source pane, preview pane, shared document.
EDSCREEN="$UI/settings/SignatureEditorScreen.kt"
EDCODE="$UI/settings/SignatureCodeEditor.kt"
EDWEB="$UI/settings/SignatureWebPreview.kt"
EDPREVIEW="$UI/settings/SignaturePreview.kt"
SETTINGS="$UI/settings/SettingsScreen.kt"
TRANSFER="$DATA/account/SignatureTransfer.kt"
HANDLER="$APP/app/src/main/kotlin/app/sterna/fleet/SignaturesSetupHandler.kt"
APPLICATION="$APP/app/src/main/kotlin/app/sterna/SternaApplication.kt"
MANIFEST="$APP/../fleet-private-data/fleet-config.json"
RES="$APP/app/src/main/res"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
absent() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail: named signatures, HTML for real, and the migration of the existing one =="

# ── N1 the editor edits the SOURCE ──
# THE defect that made "Firma (texto sin formato o HTML)" a lie: the field was bound to the FLATTENED
# text, so markup the owner typed was redrawn as plain words and lost on the next keystroke.
has "$SIGNATURE" 'fun source(): String = html.ifBlank { text }' \
  "N1 a signature's editable source is its HTML when it has one"
has "$EDSCREEN" 'SignatureEditorState(signature.source(), signature.name, isDefault)' \
  "N1 the signature field starts from the SOURCE, not from the flattened text"
has "$EDITOR" 'StoredSignature.of(id, name, source)' \
  "N1 every keystroke is handed up as the new SOURCE (re-classified by StoredSignature.of)"
absent "$EDITOR" 'value = signature.text' \
  "N1 nothing binds the field to the flattened text"

# ── N2 one discriminator for the two halves of the label ──
# A second, differently-tuned test for "is this markup" is how one string ends up rendered as tags in
# one place and as literal characters in another.
has "$SIGNATURE" 'if (looksLikeHtml(source))' \
  "N2 typed input is classified by looksLikeHtml"
has "$IDENTITY" 'looksLikeHtml(signature)' \
  "N2 the legacy split uses that same discriminator"
n=$(grep -rc 'fun looksLikeHtml' "$DATA/text/HtmlText.kt")
[ "$n" -eq 1 ] && ok "N2 looksLikeHtml is defined exactly once" \
  || bad "N2 $n definitions of looksLikeHtml -- the two halves can disagree"

# ── N3 migration, EXECUTED ──
# The failure this guards is not a broken feature: it is an upgrade that works perfectly and throws
# the owner's existing signature away. Ported from the Kotlin and run over the real cases.
python3 - "$IDENTITY" <<'MIGRATION'
import re, sys

src = open(sys.argv[1], encoding='utf-8').read()
required = [
    'fun resolvedSignatures(): List<StoredSignature>',
    'if (signatures.isNotEmpty()) return signatures',
    'if (split.signature.isBlank() && split.signatureHtml.isBlank()) return emptyList()',
    'return all.firstOrNull { it.id == defaultSignatureId } ?: all.firstOrNull()',
]
missing = [r for r in required if r not in src]
if missing:
    for m in missing:
        print('  FAIL: N3 the Kotlin this check ports no longer contains: ' + m)
    sys.exit(1)

MIGRATED_ID = "migrated"


def looks_like_html(s):
    return re.search(r'<[a-zA-Z/!]', s) is not None


def flatten(html):
    # Only ever applied to something looks_like_html already accepted; the real htmlToText does more,
    # but every case below is judged on WHICH half a value lands in, not on its exact flattening.
    return re.sub(r'<[^>]*>', '', html)


def split_signature(sig, sig_html):
    if not sig_html and looks_like_html(sig):
        return flatten(sig), sig
    return sig, sig_html


def resolved(signatures, sig="", sig_html=""):
    if signatures:
        return signatures
    text, html = split_signature(sig, sig_html)
    if not text.strip() and not html.strip():
        return []
    return [{"id": MIGRATED_ID, "name": "", "text": text, "html": html}]


def default(signatures, sig="", sig_html="", default_id=None):
    allv = resolved(signatures, sig, sig_html)
    return next((s for s in allv if s["id"] == default_id), None) or (allv[0] if allv else None)


fails = []


def check(cond, label):
    if not cond:
        fails.append(label)


# The existing single signature survives and becomes the default of the new set.
d = default([], sig="Alex Rivera\nAcme")
check(d is not None and d["text"] == "Alex Rivera\nAcme" and d["html"] == "",
      "an existing plain signature migrates to the default")
check(d is not None and d["id"] == MIGRATED_ID, "the migrated signature has the fixed, reproducible id")
check(len(resolved([], sig="Alex Rivera\nAcme")) == 1, "migration produces exactly one signature")

# An existing HTML signature keeps BOTH halves.
d = default([], sig="Alex Rivera", sig_html="<b>Alex Rivera</b>")
check(d["text"] == "Alex Rivera" and d["html"] == "<b>Alex Rivera</b>",
      "an existing HTML signature migrates with both halves")

# Legacy raw HTML sitting in the PLAIN field is split as it migrates, or the composer inserts
# angle brackets into the body as text.
d = default([], sig="<p>Alex Rivera</p>")
check(d["text"] == "Alex Rivera" and d["html"] == "<p>Alex Rivera</p>",
      "a legacy raw-HTML signature is split as it migrates")

# A FRESH install: no signature at all, and NOT a list holding one blank one -- that would show a
# nameless empty row in every picker and put a bare delimiter into every message.
check(resolved([], sig="") == [], "a fresh identity has no signatures at all")
check(default([], sig="") is None, "a fresh identity pre-selects nothing")
check(resolved([], sig="   \n ") == [], "a whitespace-only legacy signature counts as none")

# Once there ARE named signatures they win, and the default degrades the way the identity picker's
# does: the stored id, else the first.
named = [{"id": "work", "name": "Work", "text": "Alex, Acme", "html": ""},
         {"id": "home", "name": "Home", "text": "Alex", "html": ""}]
check(default(named, sig="the old one")["id"] == "work", "with no stored choice the FIRST is default")
check(default(named, default_id="home")["id"] == "home", "the stored default is honoured")
check(default(named, default_id="deleted")["id"] == "work", "a default pointing at something gone degrades to the first")

if fails:
    for f in fails:
        print("  FAIL: N3 " + f)
    sys.exit(1)
print("  ok: N3 migration and default selection, on 11 executed cases")
MIGRATION
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# The legacy fields are READ, never cleared: a downgrade must still find the owner's signature.
python3 - "$IDENTITY" <<'NODESTROY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index('fun resolvedSignatures()')
body = src[start:src.index('\n    }', start)]
# A migration that WRITES is a migration with one chance to be right; this one recomputes on read.
if 'copy(' in body or 'signature = ""' in body or 'signatureHtml = ""' in body:
    print('  FAIL: N3 resolvedSignatures rewrites the legacy fields instead of reading them')
    sys.exit(1)
print('  ok: N3 migration READS the legacy signature and never clears it')
NODESTROY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# An override edited only by adding a second signature must not be discarded as a "frozen copy".
has "$ACCOUNT" 'i.signatures, i.defaultSignatureId,' \
  "N3 the manual-identity dedup key counts the named signatures"

# ── N4 the compose picker ──
has "$VM" 'val signatureOptions: StateFlow<List<StoredSignature>>' \
  "N4 the composer exposes the identity's signatures to a picker"
has "$VM" 'suspend fun selectSignature(signature: StoredSignature): SignatureChange?' \
  "N4 choosing one reports a SignatureChange, like choosing an identity does"
has "$SCREEN" 'applySignatureChange(viewModel.selectSignature(option))' \
  "N4 the picker rewrites the body through the shared path"
has "$SCREEN" 'applySignatureChange(viewModel.selectFrom(option, isReplyOrForward))' \
  "N4 the From picker rewrites the body through that SAME path"
# ONE implementation of the rewrite, or the two pickers can disagree about an edited block.
n=$(grep -c 'fun applySignatureChange' "$SCREEN")
[ "$n" -eq 1 ] && ok "N4 exactly one implementation of the signature rewrite" \
  || bad "N4 $n implementations of the signature rewrite"
# A choice must not survive onto an identity that never had it.
has "$VM" 'available.firstOrNull { it.id == chosen } ?: identity?.defaultSignature()' \
  "N4 a choice that does not belong to the sending identity falls back to its default"

# ── N5 sanitisation on the render path ──
# The gap #206 had to close: the send path substituted the stored signature HTML VERBATIM. The
# reader's sanitiser covered received markup only, so the one kind of HTML the app generated itself
# was the one kind nothing reduced.
has "$TEXT" 'to signatureBlockHtml(signatureHtml, found.withDelimiter)' \
  "N5 the outgoing html alternative sanitises the signature"
has "$TEXT" 'return head + signatureWireHtml(signatureHtml)' \
  "N5 the wire block is built in ONE function, shared with the editor's preview"
absent "$TEXT" 'head + signatureHtml.trim())' \
  "N5 nothing substitutes the raw signature HTML any more"
has "$SIGNATURE" 'fun renderableHtml(): String = if (isHtml) signatureWireHtml(html) else ""' \
  "N5 a signature's renderable markup is sanitised at the accessor"
has "$EDPREVIEW" 'signatureBlockHtml(source, delimiter)' \
  "N5 the editor's preview shows the SANITISED markup, so a drop is visible to the owner"
# #193: an HTML signature must RENDER as HTML. The preview is a WebView with JavaScript OFF that is
# given the sanitised string, never the typed one.
has "$EDWEB" 'fun SignatureWebPreview(' \
  "N5 the editor's preview RENDERS the sanitised HTML signature (#193)"
has "$EDWEB" 'CloudWeb.sandboxedJs(view, visual)' \
  "N5 the preview WebView runs JavaScript only in visual mode, and then only its own nonce'd script"
absent "$EDITOR" 'javaScriptEnabled = true' \
  "N5 nothing turns JavaScript on in the editor"
has "$EDWEB" 'CloudWeb.create(ctx, WebProfile.UNTRUSTED_HTML)' \
  "N5 the preview WebView reads no files"
has "$TOOLS" "default-src 'none'" \
  "N5 the preview document forbids everything but pictures"
absent "$EDITOR" 'previewDocument(signature.source' \
  "N5 the preview is never fed the raw source"
# A plain-text signature must not be put through any of it.
has "$SIGNATURE" 'if (isHtml) signatureWireHtml(html) else ""' \
  "N5 a plain-text signature is never handed to the HTML pass"

# The quoted original's own coverage, restated here because THIS is the change that could have
# quietly moved it: received markup is still reduced at readerBody's single point.
has "$UI/message/ReaderBody.kt" 'ReaderBody(sanitiseReceivedHtml(it), richHtml = true)' \
  "N5 a received message's markup is still sanitised before it is rendered"

# ── N6 nine languages ──
python3 - "$RES" <<'STRINGS'
import os, re, sys
res = sys.argv[1]
added = ['compose_signature', 'compose_choose_signature',
         'settings_signature_name_label', 'settings_signature_add', 'settings_signature_default',
         'settings_signature_undo', 'settings_signature_redo', 'settings_signature_bold',
         'settings_signature_italic', 'settings_signature_underline', 'settings_signature_link',
         'settings_signature_image', 'settings_signature_colour', 'settings_signature_image_title',
         'settings_signature_colour_title', 'settings_signature_image_url_label',
         'settings_signature_colour_hex_label', 'settings_signature_plain_title']
langs = [d for d in sorted(os.listdir(res)) if d == 'values' or re.fullmatch(r'values-[a-z]{2}', d)]
missing = []
for d in langs:
    p = os.path.join(res, d, 'strings.xml')
    keys = set(re.findall(r'<string name="([^"]+)"', open(p, encoding='utf-8').read()))
    for k in added:
        if k not in keys:
            missing.append('%s/%s' % (d, k))
if missing:
    for m in missing:
        print('  FAIL: N6 ' + m + ' is missing')
    sys.exit(1)
print('  ok: N6 all %d labels this feature added exist in all %d languages' % (len(added), len(langs)))
STRINGS
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── N7 the HTML source editor ──
# The toolbar edits the SOURCE the field holds, so the stored value is exactly the text in the field.
for t in bold italic underline; do
  has "$EDSCREEN" "SignatureHtmlTools::$t" "N7 the $t button edits the source"
done
has "$EDSCREEN" 'SignatureHtmlTools.link(state.source' "N7 the link button edits the source"
has "$EDSCREEN" 'SignatureHtmlTools.image(state.source' "N7 the image-by-URL button edits the source"
has "$EDSCREEN" 'SignatureHtmlTools.colour(state.source' "N7 the colour button edits the source"
has "$EDCODE" 'visualTransformation = transformation' "N7 the source is highlighted"
has "$EDCODE" 'FontFamily.Monospace' "N7 the source is monospace"
has "$EDSCREEN" 'state.undo()' "N7 undo"
has "$EDSCREEN" 'state.redo()' "N7 redo"
has "$EDSCREEN" 'SignatureHtmlTools.plainText(state.source)' \
  "N7 the derived plain text is shown under the preview"
# Saving goes through the SAME constructor as before, so the (text, html) pair sending reads is unchanged.
has "$EDITOR" 'StoredSignature.of(id, name, source)' "N7 saving still goes through StoredSignature.of"
# The import button is gone, and nothing is left wired to it.
absent "$EDITOR" 'onImportHtml' "N7 the editor has no import callback"
absent "$SETTINGS" 'onImportHtml' "N7 Settings passes no import callback"
absent "$SETTINGS" 'importLauncher.launch("text/html")' "N7 Settings opens no HTML picker for a signature"
absent "$RES/values/strings.xml" 'name="settings_import_html"' "N7 the Import HTML label is gone"
# Only an address a signature may carry reaches the field: EXECUTED over the allowlists in the Kotlin.
python3 - "$TOOLS" <<'URLS'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'LINK_SCHEMES = setOf\(([^)]*)\)', src)
c = re.search(r'COLOUR = Regex\("([^"]*)"\)', src)
if not m or not c:
    print('  FAIL: N7 the link allowlist or the colour pattern is no longer where this check reads it')
    sys.exit(1)
schemes = set(re.findall(r'"([a-z]+)"', m.group(1)))
colour = re.compile(c.group(1))
fails = []
if schemes != {'http', 'https', 'mailto', 'tel'}:
    fails.append('link schemes are %s' % sorted(schemes))
for ok in ('#cc0000', '#CC0000'):
    if not colour.fullmatch(ok): fails.append('colour %s rejected' % ok)
for bad in ('red', '#fff', '#cc0000;x', 'url(x)', '#cc00001'):
    if colour.fullmatch(bad): fails.append('colour %s accepted' % bad)
if 'javascript' in schemes or 'data' in schemes:
    fails.append('a script-bearing scheme is allowed')
if fails:
    for f in fails: print('  FAIL: N7 ' + f)
    sys.exit(1)
print('  ok: N7 links are %s only and colours are #rrggbb only (executed)' % '/'.join(sorted(schemes)))
URLS
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── N8 the signatures travel ──
has "$TRANSFER" 'fun keyOf(email: String): String = email.trim().lowercase()' \
  "N8 a signature travels by ADDRESS, never by an account id minted per install"
has "$HANDLER" 'SetupStores.SetupHandler' "N8 the store is served by a setup handler"
has "$HANDLER" 'store.setIdentities(id, account.identities)' \
  "N8 an apply writes through the store the editor saves with"
has "$APPLICATION" 'SignaturesSetupHandler.register(container.accountStore)' \
  "N8 the application registers it with ITS account store"
python3 - "$MANIFEST" "$HANDLER" <<'STORE'
import json, re, sys
m = json.load(open(sys.argv[1], encoding='utf-8'))
s = m['stores'].get('mail_signatures')
fails = []
if s is None: fails.append('the mail_signatures store is not declared')
else:
    if s.get('class') != 'config': fails.append('class is %r, signatures are configuration' % s.get('class'))
    if s.get('used_by') != ['ac_cloud-mail']: fails.append('used_by is %r' % s.get('used_by'))
    if s.get('kind') == 'prefs': fails.append('declared as a prefs file the code never opens')
if not re.search(r'SetupStores\.register\(\s*"mail_signatures"', open(sys.argv[2], encoding='utf-8').read()):
    fails.append('no SetupStores.register("mail_signatures", ...) in the handler')
if fails:
    for f in fails: print('  FAIL: N8 ' + f)
    sys.exit(1)
print('  ok: N8 mail_signatures is declared (config, Mail) and registered under that exact name')
STORE
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo
echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
