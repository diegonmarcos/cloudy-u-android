// Cloud Canvas (#1809): see sf_core.h.
#include "sf_core.h"

#include <cstdlib>

namespace canvas {

static void applySetting(fluid_settings_t *st, const std::string &key, const std::string &value) {
    switch (fluid_settings_get_type(st, key.c_str())) {
        case FLUID_NUM_TYPE: fluid_settings_setnum(st, key.c_str(), std::strtod(value.c_str(), nullptr)); break;
        case FLUID_INT_TYPE: fluid_settings_setint(st, key.c_str(), (int) std::strtol(value.c_str(), nullptr, 10)); break;
        case FLUID_STR_TYPE: fluid_settings_setstr(st, key.c_str(), value.c_str()); break;
        default: break;  // an unknown name is ignored: the engine keeps its default
    }
}

SfSynth *sfCreate(const SfConfig &cfg, fluid_sfloader_t *(*makeLoader)(fluid_settings_t *), const char *bankPath, std::string *err) {
    auto *s = new SfSynth();
    s->settings = new_fluid_settings();
    if (!s->settings) { if (err) *err = "new_fluid_settings failed"; delete s; return nullptr; }
    for (const auto &kv : cfg.settings) applySetting(s->settings, kv.first, kv.second);
    fluid_settings_setnum(s->settings, "synth.sample-rate", cfg.sampleRate);
    fluid_settings_setint(s->settings, "synth.midi-channels", cfg.midiChannels);
    fluid_settings_setint(s->settings, "synth.audio-groups", cfg.groups);
    fluid_settings_setint(s->settings, "synth.audio-channels", cfg.groups);
    fluid_settings_setint(s->settings, "synth.effects-groups", 1);
    s->synth = new_fluid_synth(s->settings);
    if (!s->synth) { if (err) *err = "new_fluid_synth failed"; sfDestroy(s); return nullptr; }
    if (makeLoader) {
        fluid_sfloader_t *l = makeLoader(s->settings);
        if (l) fluid_synth_add_sfloader(s->synth, l);
    }
    s->sfont = fluid_synth_sfload(s->synth, bankPath, 0);
    if (s->sfont == FLUID_FAILED) { if (err) *err = std::string("the bank could not be loaded: ") + bankPath; sfDestroy(s); return nullptr; }
    s->groups = cfg.groups;
    return s;
}

void sfDestroy(SfSynth *s) {
    if (!s) return;
    if (s->synth) delete_fluid_synth(s->synth);
    if (s->settings) delete_fluid_settings(s->settings);
    delete s;
}

static fluid_preset_t *find(SfSynth *s, int bank, int program) {
    fluid_sfont_t *sf = fluid_synth_get_sfont_by_id(s->synth, s->sfont);
    return sf ? fluid_sfont_get_preset(sf, bank, program) : nullptr;
}

bool sfHasPreset(SfSynth *s, int bank, int program) { return find(s, bank, program) != nullptr; }

std::string sfPresetName(SfSynth *s, int bank, int program) {
    fluid_preset_t *p = find(s, bank, program);
    const char *n = p ? fluid_preset_get_name(p) : nullptr;
    return n ? std::string(n) : std::string();
}

bool sfProgram(SfSynth *s, int chan, int bank, int program, bool drum) {
    if (!find(s, bank, program)) return false;
    fluid_synth_set_channel_type(s->synth, chan, drum ? CHANNEL_TYPE_DRUM : CHANNEL_TYPE_MELODIC);
    return fluid_synth_program_select(s->synth, chan, s->sfont, bank, program) == FLUID_OK;
}

int sfProcess(SfSynth *s, int len, float **out, float **fx) {
    return fluid_synth_process(s->synth, len, SfSynth::kFx, fx, 2 * s->groups, out);
}

}  // namespace canvas
