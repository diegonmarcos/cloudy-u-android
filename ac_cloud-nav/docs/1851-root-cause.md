# #1851 Cloud Nav Explored + Tracker: why every day is one 24 h "sleep" stay with 0 km

Evidence is from the code at 283a50aea plus the owner's screenshots (explored-day, tracker-timeline, tracker-steps, tracker-sleep).
Items marked (inferred) cannot be proven without the phone's databases.

## Answers

**Where fixes are recorded.** Two writers, neither a poller of "every 5 minutes".
- `libs:maps` `LocationTrackerService` (foreground service, started only by the user's Start; `START_NOT_STICKY`): a PASSIVE listener always (other apps' fixes, 25 m displacement filter, 60 s floor) and, only while a wake source says MOVING, one batched BALANCED_POWER fused request (`TrackGate.FUSED` = interval 60 s, `maxUpdateDelayMs` 5 min). The owner's "about every 5 minutes" is the batch delay, not a sample rate. STILL = nothing is requested and nothing stored (`TrackGate.kt:38-84`, `LocationTrackerService.kt:190-230`). A still phone stores at most one passive heartbeat per 30 min (`HEARTBEAT_MS`), and only if another app happened to produce a fix.
- Cloud Nav `EngineRunner` (WorkManager, 15 min floor, 60 min when wake sources are armed; background, no service): GPS fix every 4 h or 2000 steps, network fix hourly, dead-reckoned `predicted` estimate every 100 steps (`EngineConfig.kt`, `EngineRunner.kt:60-100`). `EngineConfig.enabled` defaults to `false`.

**Where fixes are stored, and whether at all.** Yes, appended (`db.insert`, never upserted): `points` in `maps.db` (`MapsDb.insertPoint`, `LocationTrackerService.ingest`) and `engine_fixes` + `step_samples` in a second database `tracker_engine.db` (`EngineStore.kt` `EngineDb.addFix`). The "last location" prefs (`MapsTrackerPrefs.lastFix*`) are written in addition, not instead. So fixes are not overwritten; they are few: the displacement filter and the STILL gate mean a phone that sits at home produces ~0 rows, and the Engine is off by default.

**How a day is read back.** `DayFragment.compute` (`DayFragment.kt:79-102`) reads `TimelineSync.view` = table `timeline_segments`, a *derived* cache that `TimelineSync.run` (`TimelineSync.kt:247-262`) rebuilds only for the last 2 days, every 30 s. The day is `DayTimeline.build(store, a, b)` where `a..b` is the local day (`dayStart` with `ZoneId.systemDefault()`, consistent with the builder, so time zone is not the bug). The raw-fix path `FixSegmenter` is used only when the store has *no* segment overlapping the day (`DayFragment.kt:89`).

**How stays and moves are segmented.** `TimelineBuilder.build` (`Timeline.kt:62`) does not segment fixes. It takes the *stops* the tracker's ring-buffer detector wrote (`LocationTrackerService.emitStopAtAnchor` / `detectStop`, row in `stops`) and derives MOVEs only as the time between two stops. Moves are therefore never detected from movement; with no second stop there is no move and 0 km.

**Why the whole day collapses to one sleep stay at one place (root cause).**
1. `emitStopAtAnchor` inserts a stop with `endedAt = null`; it is closed only when a *later accepted* fix lies beyond the stop radius (`offerFix`, `LocationTrackerService.kt:258-266`). A phone that stays quiet (STILL, nothing stored, the displacement filter) never sends that fix, so the stop stays open for days.
2. `TimelineBuilder.build` ran every open stop "to now" (`b = minOf(maxOf(s.endMs ?: nowMs, ...), nowMs)`, `Timeline.kt:69` before this fix). One stop from days ago to now.
3. `isSleep` (`Timeline.kt:145`) tags a stop SLEEP when it holds >= 3 h of the 22:00-08:00 night; a multi-day stop trivially does, and the whole stop is one SLEEP segment, whatever the rest of its hours were.
4. `DayTimeline.build` clips that one segment to each day -> every day shows the same 24 h SLEEP "At Oberbaumbruecke" (the stop's place, 52.50108 13.44550), 00:00-00:00 (end = next midnight), 0 km, "time stopped 24 h". Matches explored-day.jpg and tracker-timeline.jpg: Tracker > Timeline reads the same table through `tracker/segments` (`TrackerDebugApiProvider.segments`).
(inferred) which of the two writers opened that stop; the mechanism is the same.

**Why Tracker > Sleep is 0 h except 16 h today.** `Aggregates.perDay` (`DayTimeline.kt:289-298`) counts a sleep on the date it *ends*, capped at `MAX_SLEEP_MS = 16 h`. The one open SLEEP segment ends at "now" = today, so today gets min(duration, 16 h) = 16.0 h and every earlier date has no sleep ending in it. It is not a sleep measurement at all. Health Connect sleep sessions are not read anywhere (no `SleepSessionRecord` in `ac_cloud-nav` or `libs:maps`).

**Where Explored's steps come from, and why they differ from Tracker's.** Explored > Day: `DayFragment.stepsTotal` (`DayFragment.kt:105`) = `TimelineBuilder.stepsIn` over the store's segments, whose steps come from `EngineDb.step_samples` (the phone's `TYPE_STEP_COUNTER` sampled by the 15-minute worker, `NavTimelineExtra.steps`). Those samples exist only while the Engine is enabled and the worker ran, and each slice is cut to at most 30 min (`EngineDb.slices`), so a day gets a few hundred (594). Tracker > Steps: `StepsRepository.load` reads Health Connect `StepsRecord` aggregate directly (9,910) and never touches the store. Two sources, two code paths, no reconciliation.

**Do Explored and Tracker use different stores?** Partly. Timeline, Explored Day/Trips/Places/Cities/World and Sleep read the same `timeline_segments` table (and `tracker/daily|segments|trips` wire is built from it). Steps (Health Connect, or the engine samples) and the Places digest (`StepsRepository.fixes` = `points` UNION `engine_fixes`) bypass it. Underneath there are three physical sources: `maps.db` (`points`, `stops`, `timeline_segments`), `tracker_engine.db` (`engine_fixes`, `step_samples`), and Health Connect (not stored).

## Checklist from the ticket

| Suspect | Verdict |
| --- | --- |
| cached / default location instead of fixes | No default location. The place is the open stop's own coordinates. |
| fixes overwritten | No, appended. They are just rare when still. |
| wrong day range / time zone | No; the day range and the sleep night use the same `ZoneId`. |
| segmenter thresholds swallowing everything | Not the cause: nothing runs a distance/time segmentation over fixes for the day (only the no-segment fallback does). The stop radius (50 m) + 5 min dwell are applied to the live stream only. |
| sleep heuristic tags any long stay | Yes: whole stop becomes SLEEP; capped at 16 h; counted only on the end date. |
| fixes only go to a last-location pref | No, rows are written, but the cadence (STILL stores nothing, Engine off by default) leaves almost none. |

## Fix in this change (phase 1)

- `TimelineBuilder.closeOpen`: an open stop ends at the first fix beyond 150 m, else 6 h after its last fix; never "now" after silence.
- `TimelineEngine` (`libs:maps`, pure Kotlin): fixes -> stays/moves, sleep only as night window + stillness (or a Health Connect session), per-day cut at midnight, steps per piece scaled to the day's Health Connect total, rollups.
- `maps.db` v3 table `fixes` (append-only: ts, lat, lon, accuracy, speed, provider), carried over from `points`; the tracker and the engine both append.
- Tests: `libs/maps/src/test/.../TimelineEngineTest.kt`.

## Still to do (phase 2)

Read side: Explored and Tracker pages from `fixes` -> `TimelineEngine` -> persisted visits/moves/day rollups/aggregates (one derivation, one store); Health Connect steps and sleep sessions into the store; one-shot migration of `engine_fixes` and `step_samples`; link each stop/move to its detail; recording cadence (a low-rate balanced-power sample while STILL, e.g. every 10-15 min, instead of nothing); emulator walk and on-phone check.
