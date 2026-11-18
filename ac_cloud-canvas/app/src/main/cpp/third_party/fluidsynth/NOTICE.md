# FluidSynth 2.5.1 and its libraries (Cloud Canvas > Play, #1809)

Nothing of FluidSynth is compiled in this tree. `app/build.gradle` (`fetchFluidSynth`) downloads the **official FluidSynth Android
build** at a pinned URL and sha256 (`build.json::audio.soundfont.android_zip`):

* `https://github.com/FluidSynth/fluidsynth/releases/download/v2.5.1/fluidsynth-2.5.1-android24.zip`
  sha256 `5c33eb0c6ab2de34ea255c4c39b727b87f48c490dbf4563ae24ecefbff116e69`

and packages, unmodified and as separate shared libraries in `lib/<abi>/`, only the libraries `libfluidsynth.so` needs
(`build.json::audio.soundfont.libs`). The app's own `libcanvasaudio.so` links them dynamically, so each can be replaced by a
modified build of the same name (LGPL-2.1 section 6).

| Library | Project | Licence | Source |
|---|---|---|---|
| libfluidsynth.so | FluidSynth 2.5.1 | LGPL-2.1-or-later (`LICENSE` beside this file) | https://github.com/FluidSynth/fluidsynth/tree/v2.5.1 (commit 67f0efa924ee0ec37486aa974512af5133931633) |
| libglib-2.0.so, libgthread-2.0.so | GLib 2 | LGPL-2.1-or-later | https://gitlab.gnome.org/GNOME/glib |
| libpcre.so | PCRE 8 | BSD-3-Clause | https://www.pcre.org |
| libsndfile.so | libsndfile | LGPL-2.1-or-later | https://github.com/libsndfile/libsndfile |
| libFLAC.so | FLAC (libFLAC) | BSD-3-Clause | https://github.com/xiph/flac |
| libogg.so | Ogg | BSD-3-Clause | https://github.com/xiph/ogg |
| libvorbis.so, libvorbisenc.so | Vorbis | BSD-3-Clause | https://github.com/xiph/vorbis |
| libopus.so | Opus | BSD-3-Clause | https://github.com/xiph/opus |
| liboboe.so | Oboe 1.9.0 (built by the FluidSynth release) | Apache-2.0 (`../oboe/LICENSE`) | https://github.com/google/oboe/tree/1.9.0 |

The exact versions of GLib, PCRE, libsndfile and the Xiph libraries are the ones the FluidSynth 2.5.1 Android release pipeline
pins (`.azure/azure-pipelines-android.yml` at the tag). Shown in the app: Configs > About > Credits.
