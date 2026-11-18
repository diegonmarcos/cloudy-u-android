# Oboe 1.9.0 (Cloud Canvas > Play, #1809)

`libcanvasaudio.so` (`app/src/main/cpp/canvas_audio_jni.cpp`) opens its output stream with Oboe. It is compiled against the Oboe
**1.9.0** headers, taken at build time from the pinned Maven artifact (`build.json::audio.soundfont.oboe_headers_aar`:
`https://dl.google.com/android/maven2/com/google/oboe/oboe/1.9.0/oboe-1.9.0.aar`, sha256
`e6ffd93e964e3642e7282211cca124eeee03cff1107004f33e41ba4b2ebb6d3a`), and linked against the `liboboe.so` 1.9.0 the FluidSynth 2.5.1
Android build ships (16 KB-aligned; the AAR's own library is 4 KB-aligned and is not packaged).

* Project: https://github.com/google/oboe (tag 1.9.0)
* Licence: Apache-2.0 (`LICENSE` beside this file), Copyright The Android Open Source Project.
