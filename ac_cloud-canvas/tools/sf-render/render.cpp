// Cloud Canvas (#1809): the CI offline render of the SoundFont engine. It links the SAME core as the app (app/src/main/cpp/sf_core.cpp)
// against FluidSynth built from the pinned tag, reads a plan written by tools/sf-render/plan.py from build.json::audio, and writes one
// 32-bit float stereo WAV per take, mixed the way the app's MixEngine mixes a centred track: every group at the track gain, the effects
// returns added once, then the app's master limiter (MasterLimiter in audio/Engine.kt, ceiling -1.5 dBFS; mirrored here line for line).
//
// plan lines:  rate <hz> | groups <n> | channels <n> | track_gain <g> | set <name> <value> | bank <path>
//              program <chan> <bank> <prog> <drum 0|1> | take <name> <frames> | on <frame> <chan> <key> <vel> | off <frame> <chan> <key> | end
#include "../../app/src/main/cpp/sf_core.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

struct Ev { long frame; bool on; int chan, key, vel; };

/** The app's MasterLimiter, line for line: instant attack to the ceiling, exponential release. */
struct Limiter {
    float ceiling = std::pow(10.0f, -1.5f / 20.0f);
    float gain = 1.0f, release = 0.0f;
    explicit Limiter(double rate) { release = (float) std::exp(-1.0 / (rate * 0.08)); }
    void process(float &l, float &r) {
        float peak = std::max(std::fabs(l), std::fabs(r));
        float target = peak > ceiling ? ceiling / peak : 1.0f;
        gain = target < gain ? target : target + (gain - target) * release;
        l *= gain; r *= gain;
    }
};

static void writeWav(const std::string &path, const std::vector<float> &lr, int rate) {
    std::ofstream f(path, std::ios::binary);
    auto u32 = [&](uint32_t v) { f.write((const char *) &v, 4); };
    auto u16 = [&](uint16_t v) { f.write((const char *) &v, 2); };
    uint32_t data = (uint32_t) (lr.size() * 4);
    f.write("RIFF", 4); u32(36 + data); f.write("WAVEfmt ", 8); u32(16); u16(3); u16(2); u32(rate); u32(rate * 8); u16(8); u16(32);
    f.write("data", 4); u32(data); f.write((const char *) lr.data(), data);
}

int main(int argc, char **argv) {
    if (argc != 3) { std::cerr << "usage: render <plan> <outdir>\n"; return 2; }
    std::ifstream in(argv[1]);
    canvas::SfConfig cfg;
    std::string bank, line;
    float trackGain = 0.8f;
    std::vector<std::vector<long>> programs;
    struct Take { std::string name; long frames; std::vector<Ev> ev; };
    std::vector<Take> takes;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string op; ss >> op;
        if (op == "rate") ss >> cfg.sampleRate;
        else if (op == "groups") ss >> cfg.groups;
        else if (op == "channels") ss >> cfg.midiChannels;
        else if (op == "track_gain") ss >> trackGain;
        else if (op == "set") { std::string k, v; ss >> k >> v; cfg.settings.push_back({k, v}); }
        else if (op == "bank") ss >> bank;
        else if (op == "program") { long c, b, p, d; ss >> c >> b >> p >> d; programs.push_back({c, b, p, d}); }
        else if (op == "take") { Take t; ss >> t.name >> t.frames; takes.push_back(t); }
        else if (op == "on") { Ev e{}; e.on = true; ss >> e.frame >> e.chan >> e.key >> e.vel; takes.back().ev.push_back(e); }
        else if (op == "off") { Ev e{}; e.on = false; ss >> e.frame >> e.chan >> e.key; takes.back().ev.push_back(e); }
    }
    int rate = (int) cfg.sampleRate;
    std::cout << "FluidSynth " << fluid_version_str() << ", bank " << bank << ", " << rate << " Hz, " << cfg.groups << " groups\n";
    for (auto &t : takes) {
        std::string err;
        canvas::SfSynth *s = canvas::sfCreate(cfg, nullptr, bank.c_str(), &err);
        if (!s) { std::cerr << "FAIL " << err << "\n"; return 1; }
        for (auto &p : programs)
            if (!canvas::sfProgram(s, (int) p[0], (int) p[1], (int) p[2], p[3] != 0)) { std::cerr << "FAIL preset " << p[1] << ":" << p[2] << " absent\n"; return 1; }
        std::stable_sort(t.ev.begin(), t.ev.end(), [](const Ev &a, const Ev &b) { return a.frame < b.frame; });
        const int block = 256;
        std::vector<std::vector<float>> bufs(2 * s->groups + canvas::SfSynth::kFx, std::vector<float>(block));
        std::vector<float> out;
        out.reserve(t.frames * 2);
        Limiter lim(cfg.sampleRate);
        float rawPeak = 0.0f, minGain = 1.0f;
        size_t ei = 0;
        for (long f0 = 0; f0 < t.frames; f0 += block) {
            int n = (int) std::min<long>(block, t.frames - f0);
            for (auto &b : bufs) std::fill(b.begin(), b.end(), 0.0f);
            int done = 0;
            while (done < n) {
                // events due at or before this frame, then render up to the next event (sample-exact, as MixEngine's segments)
                while (ei < t.ev.size() && t.ev[ei].frame <= f0 + done) {
                    const Ev &e = t.ev[ei++];
                    if (e.on) fluid_synth_noteon(s->synth, e.chan, e.key, e.vel); else fluid_synth_noteoff(s->synth, e.chan, e.key);
                }
                long next = ei < t.ev.size() ? t.ev[ei].frame : f0 + n;
                int len = (int) std::min<long>(f0 + n, std::max<long>(next, f0 + done + 1)) - (f0 + done);
                std::vector<float *> o(2 * s->groups), fx(canvas::SfSynth::kFx);
                for (int i = 0; i < 2 * s->groups; i++) o[i] = bufs[i].data() + done;
                for (int i = 0; i < canvas::SfSynth::kFx; i++) fx[i] = bufs[2 * s->groups + i].data() + done;
                canvas::sfProcess(s, len, o.data(), fx.data());
                done += len;
            }
            for (int i = 0; i < n; i++) {
                float l = 0, r = 0;
                for (int g = 0; g < s->groups; g++) { l += bufs[2 * g][i] * trackGain; r += bufs[2 * g + 1][i] * trackGain; }
                for (int k = 0; k < canvas::SfSynth::kFx; k += 2) { l += bufs[2 * s->groups + k][i]; r += bufs[2 * s->groups + k + 1][i]; }
                rawPeak = std::max(rawPeak, std::max(std::fabs(l), std::fabs(r)));
                lim.process(l, r);
                minGain = std::min(minGain, lim.gain);
                out.push_back(l); out.push_back(r);
            }
        }
        canvas::sfDestroy(s);
        writeWav(std::string(argv[2]) + "/" + t.name + ".wav", out, rate);
        std::ofstream(std::string(argv[2]) + "/" + t.name + ".json") << "{\"raw_peak\": " << rawPeak << ", \"limiter_min_gain\": " << minGain << "}\n";
        std::cout << "rendered " << t.name << " (" << t.frames << " frames at " << rate << " Hz, pre-limiter peak " << rawPeak << ")\n";
    }
    return 0;
}
