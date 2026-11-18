#!/usr/bin/env python3
"""Cloud Canvas (#1809): writes the CI offline-render plan for tools/sf-render/render.cpp from the app's SoundFont configuration
(app/src/main/assets/soundfont/soundfont.json, named by build.json::audio.soundfont.config_asset).

Per melodic kind (piano and the four synth waves): a chord (C major with the octave, held 2.5 s, velocity 110, then a firm velocity-127
voicing) and a two-octave C major scale up and down (eighth notes at 120 BPM). For the kit: a two-bar groove (kick, snare, closed and
open hats, the three toms, crash, ride and clap, at full velocity on the accents). And a "demo" take, the Play page's demo beat
(drums, saw bass and piano chords on three tracks at once), so the sum of several groups is measured too.

The channel layout is the app's (audio/SoundFont.kt::GmMap): channel = kind * 8 + (track - 1).
usage: plan.py <build.json> <bank.sf2> <rate> > plan.txt
"""
import json
import os
import sys

bj = json.load(open(sys.argv[1]))
sf = json.load(open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), "app/src/main/assets", bj["audio"]["soundfont"]["config_asset"])))
bank, rate = sys.argv[2], int(sys.argv[3])
kinds = sf["kinds"]
groups = sf["audio_groups"]
out = ["rate %d" % rate, "groups %d" % groups, "channels %d" % sf["midi_channels"], "track_gain 0.8", "bank " + bank]
out += ["set %s %s" % (k, v) for k, v in sf["settings"].items()]


def chan(kind, track=1):
    return kinds.index(kind) * groups + (track - 1)


for track in (1, 2, 3):
    for k in kinds:
        p = sf["presets"][k]
        out.append("program %d %d %d %d" % (chan(k, track), p["bank"], p["program"], 1 if k == "drums" else 0))

sec = lambda s: int(round(s * rate))
beat = 0.5  # 120 BPM


def take(name, seconds, events):
    out.append("take %s %d" % (name, sec(seconds)))
    for e in sorted(events, key=lambda e: e[0]):
        if e[1] == "on": out.append("on %d %d %d %d" % (sec(e[0]), e[2], e[3], e[4]))
        else: out.append("off %d %d %d" % (sec(e[0]), e[2], e[3]))


scale = [48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65, 67, 69, 71, 72]
for k in kinds:
    if k == "drums": continue
    c = chan(k)
    ev = []
    for n in (60, 64, 67, 72): ev += [(0.0, "on", c, n, 110), (2.5, "off", c, n)]
    for n in (48, 55, 60, 64, 67, 72): ev += [(3.5, "on", c, n, 127), (6.0, "off", c, n)]
    take("%s-chord" % k, 8.5, ev)
    run = scale + scale[-2::-1]
    ev = []
    for i, n in enumerate(run): ev += [(i * beat / 2, "on", c, n, 100), ((i + 0.9) * beat / 2, "off", c, n)]
    take("%s-scale" % k, len(run) * beat / 2 + 2.5, ev)

dn = sf["drum_notes"]
d = chan("drums")
ev = []
for bar in range(2):
    t0 = bar * 4 * beat
    for b in range(4):
        ev.append((t0 + b * beat, "on", d, dn["Kick" if b % 2 == 0 else "Snare"], 127 if b == 0 else 112))
        ev.append((t0 + b * beat + beat / 2, "on", d, dn["HatClosed"], 90))
        ev.append((t0 + b * beat, "on", d, dn["Ride" if bar else "HatClosed"], 100))
    ev.append((t0 + 3.5 * beat, "on", d, dn["HatOpen"], 100))
    ev.append((t0 + 3.75 * beat, "on", d, dn["Kick"], 110))
for i, k in enumerate(("TomHigh", "TomMid", "TomLow", "Clap")):
    ev.append((8 * beat + i * beat / 2, "on", d, dn[k], 120))
ev.append((10 * beat, "on", d, dn["Crash"], 127))
ev.append((10 * beat, "on", d, dn["Kick"], 127))
take("drums-groove", 10 * beat + 3.0, ev)

# the Play page's demo beat (PlayController.loadDemo): drums on track 1, saw bass on 2, piano chords on 3, two passes of 2 bars at 100 BPM
b = 0.6
ev = []
for rep in range(2):
    o = rep * 8 * b
    for i in range(8): ev.append((o + i * b, "on", chan("drums", 1), dn["Kick" if i % 2 == 0 else "Snare"], 114))
    for i in range(16): ev.append((o + i * b / 2, "on", chan("drums", 1), dn["HatClosed"], 114))
    for n, at in ((36, 0), (36, 2), (43, 4), (41, 6)):
        for j in range(2): ev += [(o + (at + j) * b, "on", chan("synth_saw", 2), n, 102), (o + (at + j + 0.9) * b, "off", chan("synth_saw", 2), n)]
    for i, chord in enumerate(((60, 64, 67), (65, 69, 72), (67, 71, 74), (64, 67, 72))):
        for n in chord: ev += [(o + i * 2 * b, "on", chan("piano", 3), n, 89), (o + (i * 2 + 1.8) * b, "off", chan("piano", 3), n)]
take("demo-beat", 16 * b + 2.5, ev)
out.append("end")
print("\n".join(out))
