# Sampled grand piano: Salamander Grand Piano V3

* **Author**: Alexander Holm (Yamaha C5, recorded at 48 kHz/24 bit, 16 velocity layers, sampled in minor thirds from A0).
* **Licence**: Creative Commons Attribution 3.0 (CC-BY 3.0), https://creativecommons.org/licenses/by/3.0/
* **Downloaded from (original public source)**: https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html,
  archive https://freepats.zenvoid.org/Piano/SalamanderGrandPiano/SalamanderGrandPianoV3+20161209_44khz16bit.tar.xz
  (the author's own release is also at https://archive.org/details/SalamanderGrandPianoV3, same licence).
* **Attribution shown in the app**: Configs > About > Credits, and the line under the Play instrument chips.

## What was changed (#1787, Cloud Canvas)

A subset: every sampled root (every 3 semitones, A0 = MIDI 21 to C8 = MIDI 108, 30 notes) at three of the 16 velocity
layers (`v4` -> `_v1` soft, `v9` -> `_v2` medium, `v14` -> `_v3` loud), 90 files. Each was mixed to mono (L+R average),
cut to 6 s (up to C3), 5 s (up to C5) or 3.5 s (above) with a 1 s fade-out, and encoded as Ogg Vorbis (quality 4, 44.1 kHz).
The app pitch-shifts between roots (at most 1.5 semitones). Encoder: ffmpeg/libvorbis.

| File | Source sample |
|---|---|
| 021_v1.ogg | 44.1khz16bit/A0v4.wav |
| 021_v2.ogg | 44.1khz16bit/A0v9.wav |
| 021_v3.ogg | 44.1khz16bit/A0v14.wav |
| 024_v1.ogg | 44.1khz16bit/C1v4.wav |
| 024_v2.ogg | 44.1khz16bit/C1v9.wav |
| 024_v3.ogg | 44.1khz16bit/C1v14.wav |
| 027_v1.ogg | 44.1khz16bit/D#1v4.wav |
| 027_v2.ogg | 44.1khz16bit/D#1v9.wav |
| 027_v3.ogg | 44.1khz16bit/D#1v14.wav |
| 030_v1.ogg | 44.1khz16bit/F#1v4.wav |
| 030_v2.ogg | 44.1khz16bit/F#1v9.wav |
| 030_v3.ogg | 44.1khz16bit/F#1v14.wav |
| 033_v1.ogg | 44.1khz16bit/A1v4.wav |
| 033_v2.ogg | 44.1khz16bit/A1v9.wav |
| 033_v3.ogg | 44.1khz16bit/A1v14.wav |
| 036_v1.ogg | 44.1khz16bit/C2v4.wav |
| 036_v2.ogg | 44.1khz16bit/C2v9.wav |
| 036_v3.ogg | 44.1khz16bit/C2v14.wav |
| 039_v1.ogg | 44.1khz16bit/D#2v4.wav |
| 039_v2.ogg | 44.1khz16bit/D#2v9.wav |
| 039_v3.ogg | 44.1khz16bit/D#2v14.wav |
| 042_v1.ogg | 44.1khz16bit/F#2v4.wav |
| 042_v2.ogg | 44.1khz16bit/F#2v9.wav |
| 042_v3.ogg | 44.1khz16bit/F#2v14.wav |
| 045_v1.ogg | 44.1khz16bit/A2v4.wav |
| 045_v2.ogg | 44.1khz16bit/A2v9.wav |
| 045_v3.ogg | 44.1khz16bit/A2v14.wav |
| 048_v1.ogg | 44.1khz16bit/C3v4.wav |
| 048_v2.ogg | 44.1khz16bit/C3v9.wav |
| 048_v3.ogg | 44.1khz16bit/C3v14.wav |
| 051_v1.ogg | 44.1khz16bit/D#3v4.wav |
| 051_v2.ogg | 44.1khz16bit/D#3v9.wav |
| 051_v3.ogg | 44.1khz16bit/D#3v14.wav |
| 054_v1.ogg | 44.1khz16bit/F#3v4.wav |
| 054_v2.ogg | 44.1khz16bit/F#3v9.wav |
| 054_v3.ogg | 44.1khz16bit/F#3v14.wav |
| 057_v1.ogg | 44.1khz16bit/A3v4.wav |
| 057_v2.ogg | 44.1khz16bit/A3v9.wav |
| 057_v3.ogg | 44.1khz16bit/A3v14.wav |
| 060_v1.ogg | 44.1khz16bit/C4v4.wav |
| 060_v2.ogg | 44.1khz16bit/C4v9.wav |
| 060_v3.ogg | 44.1khz16bit/C4v14.wav |
| 063_v1.ogg | 44.1khz16bit/D#4v4.wav |
| 063_v2.ogg | 44.1khz16bit/D#4v9.wav |
| 063_v3.ogg | 44.1khz16bit/D#4v14.wav |
| 066_v1.ogg | 44.1khz16bit/F#4v4.wav |
| 066_v2.ogg | 44.1khz16bit/F#4v9.wav |
| 066_v3.ogg | 44.1khz16bit/F#4v14.wav |
| 069_v1.ogg | 44.1khz16bit/A4v4.wav |
| 069_v2.ogg | 44.1khz16bit/A4v9.wav |
| 069_v3.ogg | 44.1khz16bit/A4v14.wav |
| 072_v1.ogg | 44.1khz16bit/C5v4.wav |
| 072_v2.ogg | 44.1khz16bit/C5v9.wav |
| 072_v3.ogg | 44.1khz16bit/C5v14.wav |
| 075_v1.ogg | 44.1khz16bit/D#5v4.wav |
| 075_v2.ogg | 44.1khz16bit/D#5v9.wav |
| 075_v3.ogg | 44.1khz16bit/D#5v14.wav |
| 078_v1.ogg | 44.1khz16bit/F#5v4.wav |
| 078_v2.ogg | 44.1khz16bit/F#5v9.wav |
| 078_v3.ogg | 44.1khz16bit/F#5v14.wav |
| 081_v1.ogg | 44.1khz16bit/A5v4.wav |
| 081_v2.ogg | 44.1khz16bit/A5v9.wav |
| 081_v3.ogg | 44.1khz16bit/A5v14.wav |
| 084_v1.ogg | 44.1khz16bit/C6v4.wav |
| 084_v2.ogg | 44.1khz16bit/C6v9.wav |
| 084_v3.ogg | 44.1khz16bit/C6v14.wav |
| 087_v1.ogg | 44.1khz16bit/D#6v4.wav |
| 087_v2.ogg | 44.1khz16bit/D#6v9.wav |
| 087_v3.ogg | 44.1khz16bit/D#6v14.wav |
| 090_v1.ogg | 44.1khz16bit/F#6v4.wav |
| 090_v2.ogg | 44.1khz16bit/F#6v9.wav |
| 090_v3.ogg | 44.1khz16bit/F#6v14.wav |
| 093_v1.ogg | 44.1khz16bit/A6v4.wav |
| 093_v2.ogg | 44.1khz16bit/A6v9.wav |
| 093_v3.ogg | 44.1khz16bit/A6v14.wav |
| 096_v1.ogg | 44.1khz16bit/C7v4.wav |
| 096_v2.ogg | 44.1khz16bit/C7v9.wav |
| 096_v3.ogg | 44.1khz16bit/C7v14.wav |
| 099_v1.ogg | 44.1khz16bit/D#7v4.wav |
| 099_v2.ogg | 44.1khz16bit/D#7v9.wav |
| 099_v3.ogg | 44.1khz16bit/D#7v14.wav |
| 102_v1.ogg | 44.1khz16bit/F#7v4.wav |
| 102_v2.ogg | 44.1khz16bit/F#7v9.wav |
| 102_v3.ogg | 44.1khz16bit/F#7v14.wav |
| 105_v1.ogg | 44.1khz16bit/A7v4.wav |
| 105_v2.ogg | 44.1khz16bit/A7v9.wav |
| 105_v3.ogg | 44.1khz16bit/A7v14.wav |
| 108_v1.ogg | 44.1khz16bit/C8v4.wav |
| 108_v2.ogg | 44.1khz16bit/C8v9.wav |
| 108_v3.ogg | 44.1khz16bit/C8v14.wav |
