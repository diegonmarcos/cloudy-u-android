# Screenshot agent: output contract (v1)

Cloud Drive's **Agents** tab runs the Screenshot agent. It OCRs every screenshot on the phone and
writes on-device ML metadata. Other fleet apps read the result as plain files in the shared store.
The first reader is Cloud Tasks' event agent.

Machine-readable source of truth: `ac_cloud-drive/data/agent-screenshots-contract.json`.

## Where
`<shared storage>/<storage.shared_root>/.agents/screenshots/v1/`, which is `/storage/emulated/0/CloudDrive/.agents/screenshots/v1/` on a single-user phone.
Readers need all-files access (MANAGE_EXTERNAL_STORAGE), which every fleet app already has for the store.

| file | content |
|---|---|
| `records/<id>.json` | one record per screenshot (schema below) |
| `index.jsonl` | append-only, one line per write: `{id, source_path, taken_at_ms, processed_at_ms, has_text}`; `{id, deleted:true}` on removal |
| `state.json` | agent bookkeeping: `{version, last_scan_ms, processed, failed, cursor_mtime_ms, total, pending}` |

`id` is the first 16 hex characters of `sha256(source_path + "\n" + size + "\n" + mtime_ms)`. An edited file gets a new id.
Every file is written as `.tmp` and then renamed, so a reader never sees a partial record.

## Record
```json
{
  "schema": "cloud-drive.agent.screenshots/1",
  "id": "9f2c1a0b7d3e4f51",
  "source_path": "/storage/emulated/0/Pictures/Screenshots/Screenshot_20261013-101500.png",
  "source_size": 482113, "source_mtime_ms": 1791886500000,
  "taken_at_ms": 1791886500000, "processed_at_ms": 1791886560000,
  "width": 1080, "height": 2400,
  "text": "Dentist\nThu 16 Oct 10:30",
  "lang": "und-Latn",
  "blocks": [{"text": "Dentist", "bbox": [40, 210, 380, 260], "lines": [{"text": "Dentist", "bbox": [40, 210, 380, 260]}]}],
  "labels": [{"label": "Text", "confidence": 0.93, "source": "image-labeling"}],
  "objects": [],
  "entities": [{"type": "date", "text": "Thu 16 Oct", "start": 8, "end": 18}, {"type": "time", "text": "10:30", "start": 19, "end": 24}],
  "engine": {"ocr": "mlkit-text-latin", "labeling": "mlkit-image-labeling", "objects": "mlkit-object-detection", "agent_version": 1},
  "error": null,
  "colours": [{"hex": "#101418", "share": 0.62}],
  "barcode": null,
  "source_app": "Chrome"
}
```
`bbox` is `[left, top, right, bottom]` in source pixels (the upright image: EXIF rotation applied).
Labels merge ML Kit image labelling and object detection (best confidence per label), then the agent's
heuristic labels (`Dark UI`/`Light UI`/`Mixed UI`, `Has text`/`Text-heavy`, `QR code`/`Barcode`).
`colours`, `barcode` and `source_app` were added in v1.1 (additive; v1 readers ignore them). `taken_at_ms` comes from EXIF, then the file name's timestamp, then mtime.

## Reading through the content provider
Instead of walking shared storage, a fleet app can query Cloud Drive directly. The provider is read-only and
guarded by a signature permission, so only an app signed with the fleet key can read it.

```xml
<uses-permission android:name="com.diegonmarcos.clouddrive.permission.READ_AGENT_RESULTS" />
<queries><package android:name="com.diegonmarcos.clouddrive" /></queries>
```

| uri | result |
|---|---|
| `content://com.diegonmarcos.clouddrive.agents.screenshots/records?since=<processed_at_ms>&limit=<n>` | rows `id, source_path, taken_at_ms, processed_at_ms, has_text, text, labels, json` (json is the whole record), live records only, in processing order |
| `content://com.diegonmarcos.clouddrive.agents.screenshots/records/<id>` | the same row for one id; `openInputStream` returns the record file |
| `content://com.diegonmarcos.clouddrive.agents.screenshots/state` | one row: `version, last_scan_ms, processed, failed, cursor_mtime_ms, total, pending` |

## Reading incrementally
Keep your own `processed_at_ms` cursor and read `index.jsonl` lines past it. For each new id, load
`records/<id>.json`. If an id appears again later, the later line wins.

## Versioning
Added fields keep v1. A breaking change bumps both the `schema` string and the `v1` directory, and the
old directory keeps being written for one release.

## How it is produced
Everything runs on the phone through the fleet's image engine (Cloud-Lib-Ml-L-Image-Mlkit.apk, reached
through libs:ml-l-image). ML Kit's Latin text recognizer reads overlapping full-width bands, so a tall
screenshot is not shrunk to 1024 px before the text is read. ML Kit image labelling, ML Kit object detection
(full-label classifier) and ZXing add the rest. A new screenshot is read seconds after it is taken (a
MediaStore trigger), a periodic pass every six hours catches anything missed, and Agents ▸ Screenshots has
Run now, Stop, Retry failed and an on/off switch.

## Sidecars (Screenshot OCR, #1507)

Besides the contract records, the agent writes two files beside every screenshot, same folder and base name:
`<name>.md` (the OCR as Markdown: headings, paragraphs, lists, tables with a header row, a front-matter block with
source, captured time, app, engine and mean confidence) and `<name>.json` (schema `cloud-drive.screenshot-ocr/1`:
file with sha256, EXIF/XMP, MediaStore fields, device, colours, OCR blocks/lines/words with boxes and confidence,
languages, tables with row and column, image labels, engine versions). The pair is skipped while the JSON's sha256
matches the image and re-written when the image changes. Engine: ML Kit Text Recognition (bundled, on-device) in
Cloud-Lib-Ml-L-Image-Mlkit; shaping in `libs:ocr`. The `.md` is written first, the `.json` last.
