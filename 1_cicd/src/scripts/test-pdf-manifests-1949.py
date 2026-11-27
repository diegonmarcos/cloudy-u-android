#!/usr/bin/env python3
"""#1949 PDF manifest tests: who is offered for a VIEW of application/pdf, and how the choices are labelled.
Writes JUnit XML (--junit) so CI reads the verdict from the XML. Run from the repo root.
  Drive and Notes offer a PDF VIEW with a PDF-specific label; Termux and Nix declare no application/* VIEW (Nix keeps its SEND; Termux has none upstream)."""
import sys, xml.etree.ElementTree as ET
NS = "{http://schemas.android.com/apk/res/android}"
Notes = "ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml"
Drive = "ac_cloud-drive/app/src/main/AndroidManifest.xml"
Termux = "ac_cloud-termux/app/src/main/AndroidManifest.xml"
Nix = "ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml"

def filters(path):
    out = []
    for a in [e for e in ET.parse(path).getroot().iter() if e.tag in ("activity", "activity-alias")]:
        for f in a.iter("intent-filter"):
            out.append((a, f, {x.get(NS + "name") for x in f.iter("action")}, [d.get(NS + "mimeType") for d in f.iter("data") if d.get(NS + "mimeType")]))
    return out

def claims(mimes, t):
    return any(m in ("*/*", t) or (m.endswith("/*") and t.startswith(m[:-1])) for m in mimes)

def view_pdf(path):
    return [(a, f) for a, f, acts, mimes in filters(path) if "android.intent.action.VIEW" in acts and claims(mimes, "application/pdf")]

def pdf_row(path):  # the filter that names application/pdf itself: the row the chooser labels
    r = [(a, f) for a, f, acts, mimes in filters(path) if "android.intent.action.VIEW" in acts and "application/pdf" in mimes]
    assert r, path + " has no filter naming application/pdf"
    return r[0]

def label(a, f):
    return f.get(NS + "label") or a.get(NS + "label") or ""

def t_drive_offers_pdf():
    pdf_row(Drive)
def t_notes_offers_pdf():
    pdf_row(Notes)
def t_termux_not_offered_pdf():
    assert not view_pdf(Termux), "Termux still claims a VIEW for application/pdf (application/*)"
def t_nix_not_offered_pdf():
    assert not view_pdf(Nix), "Nix still claims a VIEW for application/pdf (application/*)"
def t_termux_nix_keep_send():
    for p in (Nix,):
        assert any("android.intent.action.SEND" in acts for _, _, acts, _ in filters(p)), p + " lost its SEND filter"
def t_pdf_labels_distinct():
    ls = [label(*pdf_row(p)) for p in (Drive, Notes)]
    assert all(l.startswith("@string/") for l in ls), "a PDF chooser label is not a string resource: %s" % ls
    assert ls[0] != ls[1], "Drive and Notes share one PDF chooser label"
def t_pdf_label_text_names_app_and_pdf():
    import re
    for p, res, app in ((Drive, "ac_cloud-drive/app/src/main/res/values/strings.xml", "Cloud Drive"), (Notes, "ac_cloud-notes/packages/frontend/apps/android/App/app/src/main/res/values/strings.xml", "Cloud Notes")):
        name = label(*pdf_row(p)).split("/", 1)[1]
        v = next((e.text or "" for e in ET.parse(res).getroot().iter("string") if e.get("name") == name), "")
        assert app in v and "PDF" in v, "%s: label '%s' must name the app and PDF" % (app, v)

TESTS = [t_drive_offers_pdf, t_notes_offers_pdf, t_termux_not_offered_pdf, t_nix_not_offered_pdf, t_termux_nix_keep_send, t_pdf_labels_distinct, t_pdf_label_text_names_app_and_pdf]

if __name__ == "__main__":
    out = sys.argv[sys.argv.index("--junit") + 1] if "--junit" in sys.argv else "pdf-manifests.xml"
    suite = ET.Element("testsuite", name="pdf-manifests-1949", tests=str(len(TESTS)))
    bad = 0
    for t in TESTS:
        c = ET.SubElement(suite, "testcase", classname="PdfManifests", name=t.__name__)
        try: t()
        except Exception as e:
            bad += 1; ET.SubElement(c, "failure", message=str(e) or e.__class__.__name__)
    suite.set("failures", str(bad))
    ET.ElementTree(suite).write(out)
    print("%d tests, %d failures" % (len(TESTS), bad)); sys.exit(1 if bad else 0)
