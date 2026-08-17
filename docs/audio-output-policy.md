# Audio output policy

This document defines the Android behavior behind **Duck Music During Beacons**. It is the policy
boundary to preserve when the audio layer is implemented on another platform.

## Beacon and earcon modes

When ducking is enabled, every audible `AudioEngine` cue requests
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` immediately before playback. The focus request and the
`AudioTrack` both use `USAGE_ASSISTANCE_ACCESSIBILITY` with `CONTENT_TYPE_SONIFICATION`. Focus is
released only after `AudioTrack.playbackHeadPosition` passes the cue's final PCM frame. Other media
may therefore lower its volume while the cue is audible, but returns to normal between cues.

When ducking is disabled, the app makes no audio-focus request. The cue retains the same
accessibility/sonification attributes and mixes with media at its current volume.

If Android denies a ducking request, the cue still plays in mix mode. Navigation audio is an
accessibility output and must not disappear merely because focus is unavailable. Absolute silence,
stale-fix suppression, and other policies that prevent a cue from playing make no focus request.
TTS is not governed by this beacon setting.

The setting is available on Android 8.0 and later, matching this app's minimum SDK. A future
platform implementation must not expose the setting until it can provide the same observable
duck-versus-mix behavior.

## Diagnostics

Each cue log records `duckAudioEnabled` and the applied `audioMode`: `duck`, `mix`,
`mix_focus_denied`, or `suppressed`. `AUDIO_FOCUS` rows record the request result and the eventual
abandon with the measured focus-hold duration. Together they show whether the configured mode was
honored for a specific cue.

## Output stream lifetime is separate

`AudioEngine.start()` still owns a session-long `AudioTrack` and writes silence between cues to
avoid Bluetooth A2DP startup loss. That stream does not imply long-lived audio focus, and changing
the per-cue focus lifetime does not fix its dictation or power implications. Stream ownership,
silence mode, and dictation coexistence remain tracked by issue #53.
