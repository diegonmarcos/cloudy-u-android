# One Go engine for wg-go + firestack (#1881, second part)

**Status.** Design only. Nothing here is built yet; part one of #1881 (16 KB page
alignment of every Go/OCaml engine binary) is the prerequisite and is done separately.

## Today: two Go runtimes in one VPN slot family

| | `libs:net-wg` (mesh) | `libs:firewall` (per-app firewall) |
|---|---|---|
| Go code | wireguard-go, JNI `libwg-go.so` (`src/main/cpp/libwg-go`, CMake) | firestack (`libs/firewall/firestack`, celzero, MPL-2.0, owned clone) as gomobile AAR `libgojni.so` |
| Entry | `GoBackend` + `NetBackendService` (AIDL, the VPN contract) | `FirestackTunnelService` (SuperApp) |
| Size | small | ~19.45 MB `libgojni.so` |
| Runtime | one Go runtime in the net-wg process | a second Go runtime in the firewall process |

Android allows one VPN per user, so the two never run a tunnel at the same time; each
still pays a full Go runtime (heap, scheduler, GC threads) and its own page-aligned
binary. firestack already carries WireGuard proxies (it embeds wireguard-go for its
`ipn` proxies), so the mesh tunnel is a configuration of an engine we already ship.

## Target

One gomobile engine, `libgojni.so`, built from `libs/firewall/firestack` plus a thin
`mesh` package (the exact `libwg-go` surface: up, down, config set, peer stats, socket
protect). `libs:net-wg` stops compiling `libwg-go` and binds the firestack engine;
`libs:firewall` keeps its rules, decider and UI unchanged. One Go runtime, one
16 KB aligned binary, one security update path.

## Hard constraints (the contract)

1. The VPN AIDL contract of `NetBackendService` is UNCHANGED: same methods, same
   parcelables, same permission. Callers (Cloud Account mesh, SuperApp Network pages)
   need no edit.
2. Behaviour parity: every existing net-wg and firewall test is the contract; no test
   is weakened. New parity tests drive the same fixtures through both engines.
3. The old implementation stays until the new one is proven (shadow/canary below),
   then is retired in its own commit.
4. VPN consent and always-on stay with the package that owns them today (see
   `engine-apk-split.md`: moving the VPN identity between packages is the unsafe
   move); the merged engine ships in the package that already holds the mesh VPN
   identity, not in a new one.
5. No new paid infrastructure; builds in CI only (gomobile bind in a workflow, the
   AAR published like `firestack-aar-*` and consumed by checksum, never built in the
   APK job).

## Plan

1. **Surface map.** Diff the `libwg-go` exports against firestack's `ipn/wg` proxy API.
   Anything missing becomes an addition in the owned firestack tree (MPL-2.0 file
   headers kept), not a fork of wireguard-go.
2. **`mesh` package in firestack.** A gomobile-friendly facade with the libwg-go
   function set. Same config parser input as today (the wg-quick text the mesh code
   already produces).
3. **AAR build.** Extend `ship-firestack-aar.yml`: one AAR, both ABIs, linked with
   `-Wl,-z,max-page-size=16384` (as `libwg-go` is since 4433682cf), page-size guard
   enforcing it (no exemption).
4. **Shadow run.** `net-wg` gets a build flag selecting the engine; default stays the
   old JNI. A debug-only canary brings the mesh up on both engines in sequence and
   compares handshake time, peer stats and a ping through the tunnel; results go to
   the existing engine log export.
5. **Switch.** Flip the default after a green canary on a real phone (pending: the
   phone is off), keep the old path one release behind the flag.
6. **Retire.** Delete `src/main/cpp/libwg-go` and the CMake wiring in a separate
   commit once the switched release has run clean; clear the `net-wg` entries in
   `1_cicd/src/data/page-size-guard.json` if any remain.

## Risks

- Binary size: firestack is 19 MB; net-wg grows by that if it ships alone, but the
  merged engine replaces two Go runtimes, so the fleet total is expected to drop.
  Measure on the lib APK before the switch.
- wireguard-go version skew between the two today; the merged engine pins one.
- gomobile restrictions on exported types: the facade uses only primitives, strings,
  byte slices and callbacks.
