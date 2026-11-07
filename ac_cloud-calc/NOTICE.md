# Cloud Calc — licence notice

Cloud Calc is two APKs: this app (`Cloud-Calc.apk`, the screens) and the engine it binds
(`Cloud-Lib-Calc.apk`, `ab_cloud-libs-shared/libs/calc`). Every third-party component, where it
ships, and its licence:

## In Cloud-Lib-Calc.apk (statically linked into `libqalc.so`)

| Component | Version | Licence | Source |
|---|---|---|---|
| libqalculate (the engine of Qalculate!) | 5.12.0 | GPL-2.0-or-later | https://github.com/Qalculate/libqalculate |
| GNU MP (GMP) | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later | https://gmplib.org |
| GNU MPFR | 4.2.2 | LGPL-3.0-or-later | https://www.mpfr.org |
| libxml2 | 2.13.8 | MIT | https://gitlab.gnome.org/GNOME/libxml2 |
| LLVM libc++ (NDK 28.0.13004108) | — | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

The combined `libqalc.so` is distributed under **GPL-3.0-or-later**. Its complete corresponding
source is this repository: `ab_cloud-libs-shared/libs/calc/native/` (our code and the one patch
applied to libqalculate) plus the exact upstream tarballs pinned by sha256 in
`ab_cloud-libs-shared/libs/calc/data/qalc-native.json`; `ship-lib-calc-native.yml` is the build.
libqalculate's unit, constant and currency definitions (CODATA constants, ECB reference rates)
are its own data, compiled in.

## In Cloud-Calc.apk

| Component | Licence |
|---|---|
| AndroidX, Jetpack Compose, Material 3, Material icons (extended) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Fleet libraries (libs:core, libs:devtools, libs:bottomnav, libs:decisions, libs:ml-l-image) | this repository |
| AndroidX CameraX (camera-camera2, camera-lifecycle, camera-view) 1.4.2 | Apache-2.0 |
| ARCore SDK for Android (`com.google.ar:core`) 1.48.0 | Google APIs Terms of Service and the ARCore Additional Terms of Service |
| KaTeX 0.19.1 (`katex.min.js`, `auto-render.min.js`, `katex.min.css` and 13 woff2 fonts; Khan Academy and contributors) | MIT |

The LaTeX plots (#1336: the 2D plot, the 3D scene, the safe expression parser, the Graph page) are written in this
repository (`data/assets/latex/plot/*.js`, `PlotExpr.kt`, `LatexPlot.kt`, `ui/LatexScreens.kt`): own code, no third-party
library. uPlot, function-plot and three.js (all MIT) were considered and not bundled: the plots need only a canvas
and a small projection, which keeps the APK growth to the scripts themselves.

The sound meter's signal processing (radix-2 FFT, Hann window, IEC 61672-1 A-weighting, dBFS)
is written in `sound/src/main/kotlin/com/diegonmarcos/cloudcalc/sound/Dsp.kt` (#772: with C-weighting, YIN pitch, onset and event detection beside it) from the published
formulas; no code was taken from phyphox or OpeNoise (both GPL-3.0).

## The Clock tab (#768)

The Clock tab — world clock, alarms, timers, stopwatch, interval/Pomodoro, bedtime and sleep
timer — is written in this repository: `clock/` (the rules, plain JVM) and
`app/src/main/java/com/diegonmarcos/cloudcalc/clock/` plus `ui/ClockScreens.kt` (the Android half).
It uses only the platform (AlarmManager, a foreground service, MediaPlayer on the alarm stream,
RingtoneManager's picker, the device's tzdata) and AndroidX core. Its build adds PIT
(Apache-2.0, `info.solidsoft.pitest` gradle plugin, Apache-2.0) for mutation testing; neither is
packaged in the APK.

**FossifyOrg/Clock (GPL-3.0), evaluated as the source** — commit
`8fb2e86d1bee3f67c832233f6590a0127a3f024f`, 77 Kotlin files, 8,074 lines. What was taken is its
DESIGN, which this tab follows point for point: an alarm is an AlarmManager alarm *clock*
(`AlarmManagerCompat.setAlarmClock` — exact in Doze, the status-bar icon), every other wakeup an
exact allow-while-idle one, a `specialUse` foreground service keeps timers and the stopwatch alive,
the permission set is SCHEDULE_EXACT_ALARM (≤ API 32) + USE_EXACT_ALARM, and a receiver re-plans on
BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED. No Fossify source file was
copied. It was not vendored (the dialer's `patches/` mechanism) or merged because, read from its
source:

| Finding | Consequence |
|---|---|
| 50 of its 77 Kotlin files import `org.fossify.commons` (Fossify Commons 6.1.6: the Views/XML base activity, dialogs, theming, its own permission flow) | merging means compiling a second UI toolkit into a Compose app, or rewriting those 50 files — the rewrite is what this is |
| Room (2.8.5) for timers, `SharedPreferences` for alarms, greenrobot EventBus 3.3.1 between services and fragments, NumberPicker and AutoFitTextView widgets | four libraries this app does not otherwise carry, for state that fits one JSON value |
| it is an app (launcher, widgets, settings, import/export, splash), not a library | vendoring it via the patch mechanism yields a second APK, while #768 asks for a section of Cloud Calc |

## The Jev section (#770)

No third-party code. **androidx.security:security-crypto 1.1.0** (Apache-2.0) is linked for the
manual OpenRouter-token override (EncryptedSharedPreferences over an Android Keystore key), as
libs:git-sync and libs:mounts already link it. **libs:text-tools** (this repository) carries the
ITextTools binder through which the fleet Account's token is read. The request/answer shapes of
OpenRouter's Decisions API follow its public documentation (openrouter.ai/docs/guides/community/jev
and the Decisions API reference, read 2026-10-02); the #764 Jev gate (cloud-u-containers
`_shared/jev-gate`) is the fleet's other client of the same API, and this one keeps its shapes:
questions as a map of typed questions, `answers.<q>.choice` + `probabilities`, `usage.cost`, every
failure meaning "no opinion". Decision models only score; every number is computed by this app's
engines.

## Evaluated and not used (#767)

| Candidate | Licence | Why not |
|---|---|---|
| sadellie/unitto (NumberHub lineage) | GPL-3.0 | A 25-module Kotlin Multiplatform app (`sharedApp`, `androidApp`, Room, remote) with its own evaluator (`core:evaluatto`, `kt-math`). Vendoring it means a second maths engine compiled into the app — the non-GUI logic #763 keeps out of apps — and grafting the fleet shell into a different app architecture. Every mode it has is covered by libqalculate here. |
| FossifyOrg/Calculator | GPL-3.0 | A standard calculator over EvalEx (BigDecimal, no units, no CAS) plus Fossify commons and Room: a subset of libqalculate. |
| numbat | MIT / Apache-2.0 | Dimension-safe unit arithmetic is already libqalculate's (`500 W * 2 h to kWh` is a golden row); a second engine with a second unit table for one overlapping feature. |

## The Camera tab (#772)

Camera ▸ Measure / Level / Identify / Colour / Text is written in this repository: `measure/`
(the geometry, plain JVM) and `app/src/main/java/com/diegonmarcos/cloudcalc/camera/` plus
`ui/CameraScreens.kt`. Every image is READ by the shared image engine
(`Cloud-Lib-ML-L-Image-MLKit.apk`, `ab_cloud-libs-shared/libs/ml-l-image-mlkit`), which carries
Google ML Kit (barcode, text, image labeling, object detection — ML Kit Terms of Service) and
ZXing (Apache-2.0); this app compiles only the engine's thin client.

AR measuring runs on Google Play Services for AR (ARCore), which is provided by Google LLC and
governed by the Google Privacy Policy. It is optional: the app declares ARCore `optional`, never
installs it unasked, and falls back to measuring against a reference object of known size.

## The LaTeX tab (#1329)

The LaTeX tab (Calculator > LaTeX: a formula library and an editor) draws TeX with **KaTeX 0.19.1**, MIT-licensed
(https://katex.org, licence text in `data/assets/latex/katex/LICENSE`). It is bundled whole in the APK assets
(`data/assets/latex/katex/`, wired by `app/build.gradle`'s `assets.srcDirs`) and rendered in a WebView with network loads
blocked, so nothing is downloaded. MIT is compatible with this app's licence; KaTeX is MIT where JLaTeXMath for Android
(GPL-2.0 with a classpath exception) would have added a copyleft dependency. The stylesheet is KaTeX's own with
the legacy woff/ttf sources removed, and Fraktur, Script, SansSerif and Typewriter fonts are left out (their commands
fall back to the Main font); the 13 woff2 fonts that remain are the KaTeX package's, designed from the AMS Computer Modern
fonts (SIL OFL 1.1). The formula library is the owner's list "The 100 Most Important Formulas in Mathematics and Science"
(`data/latex/100-formulas.md`), turned into `formulas.json` by `data/latex/gen-formulas.mjs`.

## Date & Time: working days and holidays (#1329)

The holiday data (`data/fleet/app/com.diegonmarcos.cloudcalc/datetime/holidays.json` (served at runtime by Cloud Store, #1547)) is declared in this repository as RULES (a fixed date, an offset from
Western Easter Sunday, or the nth weekday of a month) for Spain (national plus six autonomous communities), the United Kingdom
(England), the United States (federal), Germany, France, Italy and Portugal. Public-holiday dates are facts, not copyrightable
expression; each rule was written from the countries' official calendars, and Easter is the anonymous Gregorian computus
(Meeus/Jones/Butcher), a published algorithm. No holiday library is used: Jollyday (Apache-2.0) was evaluated and not taken
because its release loads holiday definitions through JAXB/Jackson and reflection, which Android's R8 and the app's offline,
no-reflection rules make a liability for a handful of regions (see the datetime `spec` in build.json). The working-day counting is
`WorkingDays.kt`, plain JVM, and the calendar widget is a small Compose grid (`ui/DateFields.kt`); nothing is added to the APK
beyond this repository's code and one 8 KB asset.

## Time & Date: World Clock map and cities (#1423)

The world map (`data/fleet/app/com.diegonmarcos.cloudcalc/worldmap/land.json`) is Natural Earth 1:110m land (naturalearthdata.com), which is in the
public domain; it is drawn with Compose Canvas, no map library. The city catalogue (`data/fleet/app/com.diegonmarcos.cloudcalc/worldmap/cities.json`:
every country's capital and the major cities of the big countries, with country, IANA zone and coordinates) is derived
from GeoNames (geonames.org), licensed under CC BY 4.0 - attribution: "GeoNames, https://www.geonames.org, CC BY 4.0".
Both are generated by `data/gen/worldclock.py`; the zone list is the IANA tz database (public domain). See
`data/assets/worldmap/LICENSE`.
