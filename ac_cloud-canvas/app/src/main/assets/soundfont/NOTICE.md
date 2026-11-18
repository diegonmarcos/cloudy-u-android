# SoundFont bank: GeneralUser GS (#1809)

* **File**: `GeneralUser-GS.sf2`, GeneralUser GS **v2.0.3**, 32,319,396 bytes,
  sha256 `9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe` (pinned in `build.json::audio.soundfont.bank.sha256`).
* **Author**: S. Christian Collins (https://www.schristiancollins.com).
* **Licence**: GeneralUser GS License v2.0, the full text in `LICENSE.txt` beside this file. It allows use without restriction for
  music creation, private or commercial, and use in software projects; it asks that the bank be distributed as a local copy rather
  than by linking to the author's download files (this app ships its own copy).
* **Downloaded from (original public source)**: https://github.com/mrbumpy409/GeneralUser-GS (`GeneralUser-GS.sf2` and
  `documentation/LICENSE.txt` on the `main` branch, 2026-11-18).
* **Unchanged**: the file is shipped byte for byte; it is stored uncompressed in the APK and read in place by the SoundFont engine.
* **Attribution shown in the app**: Configs > About > Credits, and the engine line on the Play page.

## Engine

The bank is played by **FluidSynth 2.5.1** (https://www.fluidsynth.org, LGPL-2.1-or-later), linked dynamically from its official
Android build (`fluidsynth-2.5.1-android24.zip`, https://github.com/FluidSynth/fluidsynth/releases/tag/v2.5.1; source at the tag
`v2.5.1`, commit `67f0efa924ee0ec37486aa974512af5133931633`). The libraries are packaged unmodified as separate `.so` files
(`lib/<abi>/libfluidsynth.so` and its dependencies: GLib 2 and PCRE, libsndfile, FLAC, Ogg, Vorbis, Opus, Oboe 1.9.0), so they can be
replaced. Licences of each: `licenses/curated.json` and `licenses/inventory.json` in the repository.

`soundfont.json` beside the bank is the app's own configuration of the engine for this bank (Cloud Canvas, same licence as the app).
