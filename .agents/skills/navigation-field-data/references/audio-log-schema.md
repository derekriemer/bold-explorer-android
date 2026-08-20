# Audio log schema and reading guide

Session logs are oldest-first JSONL files named
`bold_explorer_audio_log_<timestamp>.jsonl`. Each line maps to an
`AudioLogEntry`:

```
{ "ts": <epochMs>, "kind": <Kind>, "trigger": <string>, "inputs": <string>,
  "outputs": <string>, "played": <string>, "note": <string (optional)> }
```

## Kinds

| Kind | Records |
|---|---|
| `DIRECTIONAL_BEACON` | Compass-ping inputs: target bearing, heading, relative angle, speed, smoothing |
| `ACCURACY_BEACON` | Accuracy-ring ping |
| `ALIGNMENT_PING` | Stereo alignment earcon |
| `WAYPOINT_APPROACH` | Proximity ramp-up |
| `TRAIL_COMPLETE` | Finished-trail event |
| `TTS_ANNOUNCEMENT` | Navigation, alert, collection, or recording speech |
| `DETECTION_STATE` | Detector decisions such as `OffTrailCheck`, `NearbyPoint`, and `GpsFixRejected` |
| `USER_MARKER` | User field note; `note` contains its text |

## `played` is the outcome

- `Spoke: '<text>'`: TTS was delivered while backgrounded.
- `Live region: '<text>'`: foregrounded; TalkBack delivered the UI live
  announcement. This is expected, not a missing announcement.
- `Suppressed: '<text>'`: archived logs before 2026-08-15 use this older name
  for the same foreground live-region outcome.
- `Not spoken, app in foreground: '<text>'`: the direct waypoint-approach or
  trail-complete path did not have a live region.
- Earcon labels mean the tone played; `bail:<reason>` means the detector chose
  not to act; an empty value is informational only.

`OutputDisposition.playedLabel` in `OutputRouter.kt` produces the four spoken
outcomes above; rewording it changes every corresponding log line.

## Investigation order

1. Start with `USER_MARKER` rows; they are the highest-signal observation.
2. Check `TTS_ANNOUNCEMENT`: `ttsDelivered=true` means background speech;
   `Live region:` and legacy `Suppressed:` mean foreground delivery.
3. Look for clusters of `DETECTION_STATE` bails. Repeated
   `bail:no_relative_deg` means an off-trail detector had no usable heading.
4. Find duplicate trigger/target events occurring within seconds; these often
   indicate a repeatedly crossed proximity threshold.
5. Gaps over 30 seconds without `DIRECTIONAL_BEACON` usually mean the app was
   paused or GPS duty-cycled while the screen was off.

`inputs` and `outputs` are `key=value` lists. Useful directional input fields
are `relativeDeg`, `userSpeed_ms`, `smoothedHeadingDeg`, `rawHeadingDeg`,
`smoothedConfidence`, and `courseIsSmoothed`.

## Position schemas

| Schema | Position | Available on | Geometry |
|---|---|---|---|
| v1 (before 2026-08-12) | top-level `userLat` / `userLng` | announcements, beacons, detections | reconstruct from `targetIndex` / `targetLat` |
| v2 (`"v":2`, 2026-08-12+) | `extra.lat` / `extra.lon` | `TRAIL_MATCH` only | requires the trail GPX |

A v2 announcement has no position of its own; use the nearest `TRAIL_MATCH`.
