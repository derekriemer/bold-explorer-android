# Recorded-walk scenario tests

Recorded walks are replayable navigation fixtures, not merely debugging data.
Field reports found the defects this suite has fixed, so preserve tests that
exercise real failures as well as reduced synthetic cases.

Fixtures and the harness are under
`shared/src/commonTest/.../navigation/scenario/`. `WalkScenario` represents one
session, `RecordedWalkTest` collects them, and `ScenarioRunner` mirrors
`GpsViewModel`: start following, feed each fix (course and match), then run
guidance and detectors.

## Privacy is required

Fixtures must contain no coordinates, names, marker text, or absolute dates.
The raw logs, GPX, and database copy stay outside the repository. The generator
converts coordinates to a local metre frame, rotates it per scenario, reanchors
it on a synthetic origin, and turns timestamps into offsets. Its input data is
read only at generation time, so a fixture must run on a fresh clone with no
corpus installed.

```bash
tools/build-scenario.py <log>.jsonl --list
tools/build-scenario.py <log>.jsonl --session 1 --name X \
    --trail-gpx trail_12.gpx \
    --description "..." --out shared/src/commonTest/.../scenario/X.kt
```

For v2 logs, supply the GPX; v1 logs contain enough target data. Do not make
the generated test depend on source data at runtime.

## Assertions

Label each walk from its geometry and user markers before asserting expected
behaviour. A plausible narrative can be wrong. Prefer a bounded positive claim
such as exactly one alert in a verified window over a blanket claim that no
alert should occur. Keep at least one assertion that requires an alert, or a
detector that never fires could pass the suite.
