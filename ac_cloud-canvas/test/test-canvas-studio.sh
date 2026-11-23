#!/usr/bin/env bash
# test-canvas-studio (#1865): Draw's pro tooling, Play as a studio, Record, Looping Play and Cymatics. No build and no network.
#   U1 Draw is canvas-first: the canvas is drawn first and fills the page; the floating top toolbar offers Menu, Undo, Redo, Tools, Brush
#      library, Colour, Layers and Fullscreen; the vertical rail has the Size and Opacity bars and Brushes.rail is exactly pencil, marker,
#      brush, airbrush, ink, smudge, eraser; the colour dialog draws the colour wheel (ColorWheel.hueSat); the layers panel is kept; the
#      menu keeps every action of the old actions row (Open, Export PNG, Folder, Share, the canvas size)
#   U2 every earlier tool keeps its place and ordinal (Brush .. Eyedropper first, in order) and the new brushes are appended (Airbrush,
#      Ink, Smudge); the renderer draws each new brush (drawAirbrush, drawInk, drawSmudge)
#   U3 Play is a studio: the arranger (instrument and audio rows, coloured clips, the ruler from Arrangement.ruler, the playhead), the
#      transport (Play/Stop, REC, Looping Play, tempo, metronome, loop length), the panel buttons (Mixer, Instruments, Keyboard, Pads,
#      Cymatics) and a two-octave keyboard whose C keys carry their octave label
#   U4 Record and Looping Play reach the engine: microphone takes (MicRecorder, AudioRecord) are mixed by the engine at their loop position
#      (takePcm), the engine clock repeats the Looping Play region (s.loopRegion()), overdub keeps recording and merges (mixer.overdub /
#      LoopRegion.merge), and the WAV export carries the takes
#   U5 Cymatics: the plate follows the dominant frequencies of the master output while the transport runs (Spectrum.dominant over the
#      engine's analysis ring) and the last played note otherwise (Pitch.freq); the figure is Cymatics.blend, the Chladni sum
#      cos(n pi x) cos(m pi y) + cos(m pi x) cos(n pi y)
#   U6 the pure logic stays pure (no android import in audio/Studio.kt and draw/Brushes.kt) and its JVM tests exist: StudioTest (note to
#      frequency, region timing and wrap, overdub merge, tracks and clips, cymatics determinism, symmetry and change with frequency, the
#      spectrum, the region-aware clock, the take resampling) and BrushTest (rail, ink, airbrush, smudge mix, colour wheel)
# Each rule is then proven red by a planted mutation in a scratch copy.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {  # $1 = repo root holding ac_cloud-canvas and ab_cloud-libs-shared; prints one line per failed rule
python3 -I - "$1" <<'PY'
import os, re, sys
root = sys.argv[1]
J = "src/main/java/com/diegonmarcos/cloudcanvas/"
L = os.path.join(root, "ab_cloud-libs-shared/libs")
def rd(*p):
    f = os.path.join(*p)
    return open(f, encoding="utf-8").read() if os.path.isfile(f) else ""
draw = rd(L, "canvas-draw", J, "ui/DrawScreen.kt")
model = rd(L, "canvas-draw", J, "draw/Model.kt")
brushes = rd(L, "canvas-draw", J, "draw/Brushes.kt")
renderer = rd(L, "canvas-draw", J, "draw/DocRenderer.kt")
play = rd(L, "canvas-play", J, "ui/PlayScreen.kt")
ctl = rd(L, "canvas-play", J, "ui/PlayController.kt")
engine = rd(L, "canvas-play", J, "audio/Engine.kt")
mixer = rd(L, "canvas-play", J, "audio/Mixer.kt")
studio = rd(L, "canvas-play", J, "audio/Studio.kt")
mic = rd(L, "canvas-play", J, "audio/MicRecorder.kt")
tests = rd(root, "ac_cloud-canvas/app/src/test/java/com/diegonmarcos/cloudcanvas/StudioTest.kt") + rd(root, "ac_cloud-canvas/app/src/test/java/com/diegonmarcos/cloudcanvas/BrushTest.kt")
# U1
body = draw[draw.find("fun DrawScreen("):draw.find("private val WHEEL_COLORS")]
if not body or body.find("DrawCanvas(ctl)") < 0 or body.find("DrawCanvas(ctl)") > body.find("draw_toolbar"): print("U1 the canvas is not drawn first, under the toolbar")
bar = re.findall(r'ToolbarButton\([\w.]+, "([^"]+)"', body)
for w in ("Menu", "Undo", "Redo", "Tools", "Brush library", "Layers", "Fullscreen"):
    if w not in bar: print("U1 the floating toolbar lacks " + w)
if 'contentDescription = "Colour"' not in body: print("U1 the floating toolbar lacks the colour button")
for w in ('Text("Open")', 'Text("Export PNG")', 'Text("Folder")', 'Text("Share")', 'Text("Canvas ${'):
    if w not in body: print("U1 the menu lacks " + w)
if 'VerticalBar("Size"' not in draw or 'VerticalBar("Opacity"' not in draw or "Brushes.rail.forEach" not in draw: print("U1 the rail lacks the size bar, the opacity bar or the brushes")
rail = re.findall(r'RailBrush\("(\w+)"', brushes)
if rail != ["pencil", "marker", "brush", "airbrush", "ink", "smudge", "eraser"]: print("U1 the rail is %r" % rail)
if "ColorWheel.hueSat(" not in draw or "Brush.sweepGradient(WHEEL_COLORS" not in draw: print("U1 the colour dialog has no colour wheel")
if "private fun LayersPanel(" not in draw or "LayersPanel(ctl," not in body: print("U1 the layers panel is gone")
# U2
m = re.search(r"enum class Tool\(val label: String\) \{(.*?);", model, re.S)
tools = re.findall(r"(\w+)\(\"", m.group(1)) if m else []
if tools[:10] != ["Brush", "Pencil", "Marker", "Eraser", "Fill", "Line", "Rect", "Ellipse", "Text", "Eyedropper"] or tools[10:] != ["Airbrush", "Ink", "Smudge"]: print("U2 the tools are %r" % tools)
for f in ("drawAirbrush(c, op)", "drawInk(c, op)", "drawSmudge(c, bmp, op)"):
    if f not in renderer: print("U2 the renderer does not call " + f)
# U3
for w in ("private fun Arranger(", "Arr.ruler(loopBeats)", "canvas_play_arranger", "canvas_play_ruler", "Arr.rows(s.mixer", "Arr.Kind.Audio", "Color(c.color)",
          "drawLine(Color.White, Offset(beat * w, 0f)", 'Text("Looping Play")', "RecButton(", '"REC"', 'Text("Metronome")', 'label = "Mixer"', 'label = "Instruments"', 'label = "Keyboard"', 'label = "Pads"', 'label = "Cymatics"'):
    if w not in play: print("U3 the studio lacks " + w)
if "intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)" not in play or "Notes.name(base + off)" not in play: print("U3 the keyboard is not two labelled octaves")
# U4
if "AudioRecord(" not in mic or "fun stop(): FloatArray" not in mic: print("U4 MicRecorder does not record the microphone")
if "takePcm[take.id]" not in engine or "clock.loopPos(frame + i) - start" not in engine: print("U4 the engine does not mix the microphone takes at their loop position")
if "val region = s.loopRegion()" not in engine or "region.startBeat, region.endBeat" not in engine: print("U4 the engine clock ignores the Looping Play region")
if "!s.overdub" not in engine or "mixer.overdub(" not in engine or "LoopRegion.merge(" not in mixer: print("U4 overdub does not keep recording and merge")
if "takes = HashMap(engine.takePcm)" not in ctl: print("U4 the WAV export drops the microphone takes")
# U5
if "Spectrum.dominant(analysisBuf" not in ctl or "if (s.playing)" not in ctl or "Pitch.freq(midi)" not in ctl: print("U5 the cymatics drive does not follow the music and the played note")
if "Cymatics.blend(d, grid, t)" not in play or "ctl.cymaticsDrive()" not in play: print("U5 the cymatics view does not draw the plate")
if "cos(n * PI * x) * cos(m * PI * y) + cos(m * PI * x) * cos(n * PI * y)" not in studio: print("U5 the plate is not the symmetric Chladni sum")
if "440.0" not in studio or "2.0.pow((midi - 69) / 12.0)" not in studio: print("U5 note to frequency is not A4 = 440 equal temperament")
# U6
for name, src in (("audio/Studio.kt", studio), ("draw/Brushes.kt", brushes)):
    if not src or re.search(r"^import android\.", src, re.M): print("U6 %s is missing or imports Android" % name)
for t in ("noteToFrequencyIsEqualTemperamentAtA440", "keyboardLabelsNameTheOctave", "regionPositionWrapsAndCountsPasses", "regionWindowFindsEventsAcrossTheWrapAndSkipsOutsideOnes",
          "overdubMergeKeepsEverythingAndOnlyALouderDuplicateReplaces", "overdubMergeWrapsATakePastTheRegionEndAndSorts", "notesGroupIntoColouredClipsAndNoNoteIsLost",
          "rowsListEveryInstrumentTrackThenTheAudioTracks", "cymaticsIsDeterministicPerFrequency", "cymaticsIsSymmetric", "cymaticsChangesWithFrequency",
          "cymaticsOfSeveralFrequenciesBlendsThem", "spectrumFindsTheDominantFrequencies", "loopClockRepeatsTheRegionAndKeepsTheWholeLoopByDefault",
          "microphoneTakesAreResampledForTheExport", "theRailHoldsTheSevenBrushesAndTheGridEveryTool", "inkIsWideWhenSlowAndThinWhenFast",
          "airbrushSprayIsDeterministicInsideItsDisc", "smudgeMixesColoursChannelByChannel", "colourWheelMapsAngleToHueAndRadiusToSaturation"):
    if "fun " + t + "()" not in tests: print("U6 missing unit test " + t)
PY
}

echo "== the real tree =="
OUT="$(check "$ROOT")"
[ -z "$OUT" ] && ok "U1-U6 hold on Cloud Canvas" || { bad "rules broken:"; echo "$OUT"; }

S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mut() {  # $1 = label, $2 = rule prefix expected, $3.. = edit of the scratch copy (run in the scratch repo root)
  local label="$1" want="$2"; shift 2
  rm -rf "$S/r"; mkdir -p "$S/r/ab_cloud-libs-shared/libs" "$S/r/ac_cloud-canvas/app/src"
  cp -r "$ROOT"/ab_cloud-libs-shared/libs/canvas-* "$S/r/ab_cloud-libs-shared/libs/"; cp -r "$APP/app/src/test" "$S/r/ac_cloud-canvas/app/src/"
  ( cd "$S/r" && "$@" )
  if check "$S/r" | grep -q "^$want"; then ok "mutation '$label' is caught ($want)"; else bad "mutation '$label' went undetected"; fi
}
D=ab_cloud-libs-shared/libs/canvas-draw/src/main/java/com/diegonmarcos/cloudcanvas
P=ab_cloud-libs-shared/libs/canvas-play/src/main/java/com/diegonmarcos/cloudcanvas
T=ac_cloud-canvas/app/src/test/java/com/diegonmarcos/cloudcanvas
mut "toolbar loses Redo"          U1 sed -i 's/"Redo", ink, enabled/"Again", ink, enabled/' $D/ui/DrawScreen.kt
mut "menu loses Share"            U1 sed -i 's/text = { Text("Share") }/text = { Text("Send") }/' $D/ui/DrawScreen.kt
mut "rail loses smudge"           U1 sed -i '/RailBrush("smudge"/d' $D/draw/Brushes.kt
mut "rail reordered"              U1 sed -i 's/RailBrush("pencil", "Pencil", Tool.Pencil),/RailBrush("ink0", "Pencil", Tool.Pencil),/' $D/draw/Brushes.kt
mut "no colour wheel"             U1 sed -i 's/ColorWheel.hueSat(/ColorWheelX.hueSat(/g' $D/ui/DrawScreen.kt
mut "layers panel dropped"        U1 sed -i 's/LayersPanel(ctl, Modifier.align/Unit; Modifier.align/' $D/ui/DrawScreen.kt
mut "an old tool moved"           U2 sed -i 's/Brush("Brush"), Pencil("Pencil")/Pencil("Pencil"), Brush("Brush")/' $D/draw/Model.kt
mut "smudge not rendered"         U2 sed -i 's/Tool.Smudge -> drawSmudge(c, bmp, op)/Tool.Smudge -> drawStroke(c, op)/' $D/draw/DocRenderer.kt
mut "no arranger"                 U3 sed -i 's/Arranger(ctl, s, beat, Modifier/Box(Modifier/; s/private fun Arranger(/private fun Unused(/' $P/ui/PlayScreen.kt
mut "no playhead"                 U3 sed -i 's/drawLine(Color.White, Offset(beat \* w, 0f)/drawLine(Color.Gray, Offset(0f, 0f)/g' $P/ui/PlayScreen.kt
mut "no REC"                      U3 sed -i 's/"REC"/"Go"/' $P/ui/PlayScreen.kt
mut "one octave keyboard"         U3 sed -i 's/intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)/intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)/' $P/ui/PlayScreen.kt
mut "takes not mixed"             U4 sed -i 's/takePcm\[take.id\]/emptyMap<Int, FloatArray>()[take.id]/' $P/audio/Engine.kt
mut "region ignored"              U4 sed -i 's/region.startBeat, region.endBeat)/0.0, -1.0)/' $P/audio/Engine.kt
mut "overdub stops after a pass"  U4 sed -i 's/s.recording \&\& !s.overdub \&\& recordFrom/s.recording \&\& recordFrom/' $P/audio/Engine.kt
mut "export drops takes"          U4 sed -i 's/takes = HashMap(engine.takePcm), //' $P/ui/PlayController.kt
mut "cymatics ignores the music"  U5 sed -i 's/Spectrum.dominant(analysisBuf/listOf<Pair<Double,Double>>(); Spectrum.dominant(FloatArray(0)/' $P/ui/PlayController.kt
mut "antisymmetric plate"         U5 sed -i 's/cos(n \* PI \* x) \* cos(m \* PI \* y) + cos(m \* PI \* x) \* cos(n \* PI \* y)/cos(n * PI * x) * cos(m * PI * y) - cos(m * PI * x) * cos(n * PI * y)/' $P/audio/Studio.kt
mut "wrong tuning"                U5 sed -i 's/2.0.pow((midi - 69) \/ 12.0)/2.0.pow((midi - 60) \/ 12.0)/' $P/audio/Studio.kt
mut "studio logic imports android" U6 sed -i 's/^import kotlin.math.PI$/import android.util.Log\nimport kotlin.math.PI/' $P/audio/Studio.kt
mut "symmetry test removed"       U6 sed -i 's/fun cymaticsIsSymmetric()/fun x()/' $T/StudioTest.kt
mut "merge test removed"          U6 sed -i 's/fun overdubMergeKeepsEverythingAndOnlyALouderDuplicateReplaces()/fun y()/' $T/StudioTest.kt
mut "brush test removed"          U6 sed -i 's/fun airbrushSprayIsDeterministicInsideItsDisc()/fun z()/' $T/BrushTest.kt

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
