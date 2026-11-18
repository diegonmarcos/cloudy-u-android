// Cloud Canvas (#1809): the SoundFont engine core shared by the app (canvas_audio_jni.cpp, FluidSynth 2.5.1 from its official Android
// build) and the CI offline render (tools/sf-render/render.cpp, FluidSynth 2.5.1 built from the pinned tag). One place creates the
// synth from build.json::audio.soundfont.settings, loads the bank, selects presets and renders, so the render the CI measures is the
// render the phone plays. Pure C++ over the public FluidSynth API: no JNI and no Android header here.
#pragma once

#include <fluidsynth.h>

#include <string>
#include <utility>
#include <vector>

namespace canvas {

/** What the synth is built with. [settings] are FluidSynth setting names and values as text (typed by the setting's own type). */
struct SfConfig {
    double sampleRate = 48000.0;
    /** Stereo output groups: one per mixer track (build.json::audio.soundfont.audio_groups). A MIDI channel c renders into group c % groups. */
    int groups = 8;
    int midiChannels = 64;
    std::vector<std::pair<std::string, std::string>> settings;
};

struct SfSynth {
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;
    int sfont = -1;
    int groups = 0;
    /** The effects buffers fluid_synth_process fills: reverb L/R and chorus L/R of the one effects group. */
    static constexpr int kFx = 4;
};

/** Creates the synth and loads [bankPath] (a file path, or a name an installed [loader] understands). Null and [err] set on failure. */
SfSynth *sfCreate(const SfConfig &cfg, fluid_sfloader_t *(*makeLoader)(fluid_settings_t *), const char *bankPath, std::string *err);

void sfDestroy(SfSynth *s);

/** True when the loaded bank holds the preset (bank, program). */
bool sfHasPreset(SfSynth *s, int bank, int program);

/** The preset's name in the bank, empty when absent. */
std::string sfPresetName(SfSynth *s, int bank, int program);

/** Puts (bank, program) on MIDI channel [chan]; a drum channel is switched to the drum type first. False when the preset is absent. */
bool sfProgram(SfSynth *s, int chan, int bank, int program, bool drum);

/** Renders [len] frames, ADDING into out[0..2*groups) (L, R of each group) and fx[0..kFx) at their current pointers. */
int sfProcess(SfSynth *s, int len, float **out, float **fx);

}  // namespace canvas
