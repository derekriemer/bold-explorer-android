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

## Output stream lifetime

The app opens an `AudioTrack` only when an earcon is about to play. It writes a 60 ms silent
pre-roll to give a newly opened Bluetooth route a chance to warm, then the earcon, waits for its
final audible frame, and releases the track. The pre-roll is bounded to that one cue: no digital
silence, output stream, or audio focus remains active between cues. Device verification decides
whether 60 ms is sufficient for a particular route.

Absolute silence and stale-fix suppression create no track. Stopping navigation interrupts and
releases the one active track, if any. `AUDIO_OUTPUT` log rows record every start, unavailable
start, and stop alongside the `AUDIO_FOCUS` rows, so an exported session can demonstrate that idle
navigation does not own audio output.

## Device verification

1. Start navigation, wait at least 30 seconds with no cue playing, then use an in-app dictation
   field. Repeat with a Bluetooth headset. Dictation should behave as it does with navigation
   stopped.
2. Repeat with music playing and ducking both enabled and disabled. Confirm the cue remains intact
   after the short Bluetooth pre-roll, and that music ducks only in the enabled pass.
3. Export the audio log. Each audible cue should have paired `AUDIO_OUTPUT` `STARTED`/`STOPPED`
   rows; there must be no output row covering the idle interval.
