# 3S

Once a day, while you are wearing your Ray-Ban Meta glasses, your Pixel Watch buzzes.
Tap **Record 3s** and three seconds of your day are captured from the glasses and filed
away. At the end of the month, stitch them into one montage.

`de.freal.threeseconds`

## How it works

```
  window opens
      │
      ▼
  attempt 1: random point in the first 2h of the window
      │
      ▼
  ┌─ sample: are the glasses on? ─┐
  │                               │
 no                              yes
  │                               │
  ▼                               ▼
attempt n+1: random point    prompt + 60s countdown
within the next 60 min            │   bridges to the watch
  │                  ┌────────────┼────────────┐
  └─ repeat until    │            │            │
     window closes   │            │            │
         │     Record 3s     Snooze 5m     countdown
         │           │        (×2 max)      expires
         ▼           ▼            │            │
     NOT_WORN  RecordingService   ▼            ▼
   streak kept  HEVC → MediaMuxer +5 min     MISSED
                     │                   streak breaks
                     ▼
         Movies/ThreeSeconds/3s_….mp4
```

Four design decisions carry most of the weight:

**Front-loaded, stepping cadence.** The first attempt is drawn from the opening stretch
of the window, and every re-roll steps forward by at most one step. Drawing uniformly
across the whole window instead meant one unlucky late draw could skip the entire day and
then cram every retry into the last hour; stepping keeps the attempts spread evenly no
matter where the dice land, and the number of tries follows the window length rather than
a fixed cap.

**No Wear OS app.** The prompt is an ordinary phone notification, which Android bridges
to the watch automatically. Its action buttons are backed by `PendingIntent.getBroadcast`,
so tapping *Record 3s* on your wrist runs the receiver **on the phone** — no "open on
phone" hand-off, no unlocking. Tapping a notification action is also an exemption from
the Android 12+ background foreground-service restriction, which is what lets the capture
service start while the phone is asleep in your pocket.

**Sampling, not waiting.** The trigger reads whether the glasses are on *at* the chosen
moment rather than listening for you to put them on. Watching for a `DONNED` transition
would make the prompt arrive seconds after you reach for your glasses — predictable, and
expensive to listen for. Sampling keeps the timing independent of your behaviour, and the
whole check fits inside a broadcast receiver's budget, so nothing stays resident between
attempts. A day costs a handful of alarm wakeups instead of hours of a foreground service.

**HEVC passthrough.** The DAT SDK has no "record to file" API — it exposes a frame
stream. Configuring the stream with `compressVideo = true` delivers already-encoded HEVC,
which goes straight into `MediaMuxer`. No decode, no re-encode, no second generation of
loss on top of what the Bluetooth bandwidth ladder already costs, and the file is written
as fast as the frames arrive.

Clip length is measured from frame timestamps, not wall clock, so the seconds spent
waking the Bluetooth link never eat into your three seconds.

## Setup

### 1. Developer Mode

The app talks to the glasses through the Meta AI app.

1. Install the **Meta AI** app on the phone and pair your glasses.
2. Enable **Developer Mode** in the Meta AI app.

In Developer Mode the SDK skips attestation, so the placeholders in
`app/build.gradle.kts` can stay at `0`:

```kotlin
manifestPlaceholders["mwdat_application_id"] = "0"
manifestPlaceholders["mwdat_client_token"] = "0"
```

For a production build, replace both with the credentials for your app from the
[Wearables Developer Center](https://wearables.developer.meta.com/).

### 2. Build and install

```bash
./gradlew :app:installDebug      # debug build, attachable debugger
./gradlew :app:installRelease    # release build, for actually living with it
```

With more than one device attached, prefix either with `ANDROID_SERIAL=<serial>`
(`adb devices -l` lists them).

The release build is signed with the **debug key** so it can be installed at all — an
unsigned APK cannot be. It shares the applicationId and signature with the debug build,
so the two replace each other in place and keep the same app data. Swap in a real
keystore before distributing anything: Play rejects debug-signed APKs, and a properly
signed build cannot upgrade a debug-signed one in place afterwards.

Or use the helper, which picks a device from a menu by model name rather than making you
type a wireless adb serial:

```bash
./scripts/deploy.sh debug            # install + launch, no debugger
./scripts/deploy.sh release          # the real build
./scripts/deploy.sh debug --logcat   # and tail the app's logs
```

With one device attached it does not ask. The same commands are wired into VS Code's
**Run and Debug** list and task list.

Breakpoints are a separate entry (*debug with breakpoints*), because the Android
extension launches with `am start -D` — the app stays suspended and invisible until the
debugger attaches, so a failed attach looks exactly like the app never starting. The
`run` entries avoid `-D` entirely; to debug a running app, start it with one of those
and then use *attach to running app*.

### 3. First run

1. Open the app and tap **Connect glasses** — this registers it with Meta AI.
2. Tap **Allow camera** to grant the glasses' camera to this app.
3. Allow notifications when prompted (required for the watch to ever see anything).
4. Check the **Settings** tab and set the window you want to be asked in.

Make sure notifications are mirrored to your watch in the Watch companion app,
otherwise the prompt will stay on the phone.

## Behaviour

| Thing | Rule |
|---|---|
| First attempt | A random instant in the **first 2 hours** of your window |
| Each re-roll | A random instant within the **next 60 minutes**, repeating until the window closes |
| At each attempt | Checks whether the glasses are being worn (`DonState.DONNED`) — a single read, nothing resident in between |
| The ask | A notification with a live **60 second countdown**, ticking down on the watch face |
| Snooze | A fixed **+5 minutes**, twice a day, then the button disappears. Never a new random draw, and it does not spend an attempt |
| Glasses unavailable | Never falls back to the phone camera — this is a glasses app |
| Where clips go | `Movies/ThreeSeconds/` (visible in your gallery, survives uninstall) |
| Montages | `Movies/ThreeSeconds/Montages/` |

### What a streak actually requires

A streak is about answering when asked, not about recording every day.

| Day ends as | Streak |
|---|---|
| `RECORDED` — a clip was captured | kept |
| `ATTEMPTED` — you tapped Record, the glasses failed | kept |
| `NOT_WORN` — the window closed without ever catching the glasses on | **kept** — you were never asked, so there was nothing to miss |
| `MISSED` — the prompt arrived and the countdown ran out | **broken** |
| `SNOOZED` — snoozed and the day never resolved | broken |

`MISSED` is the only outcome you can cause. A day spent without the glasses costs
nothing. If a prompt does expire, the day still accepts a manual recording from the
app — that overwrites it back to `RECORDED`.

### Tuning the cadence

Nothing here is a clock time, so changing your window in Settings changes everything
downstream. The two cadence knobs live in `AppSettings` and are persisted, expressed as
minutes *relative to the window*:

| Setting | Default | Effect |
|---|---|---|
| `firstAttemptSpreadMinutes` | 120 | How far into the window the first attempt may land |
| `retryStepMinutes` | 60 | How far apart subsequent attempts may be |

Both clamp to the window, so a short window still behaves. A 12-hour window at the
defaults gives roughly eleven attempts across the day. `MAX_ATTEMPTS_PER_DAY` is a safety
valve against pathological configurations, not the real limit — the window is.

## Tests

```bash
./gradlew :app:testDebugUnitTest          # streak rules
./gradlew :app:connectedDebugAndroidTest  # needs a device or emulator
```

The instrumentation tests run against **MockDeviceKit**, so they need no glasses. They
encode a synthetic HEVC clip on device, feed it through the mock camera, and assert the
recorder produces a genuinely playable MP4 — the capture and montage paths both hand-roll
MediaMuxer, so they are checked sample by sample rather than trusted.

## Layout

| Path | What |
|---|---|
| `glasses/ClipRecorder.kt` | Session → camera → N seconds of frames |
| `glasses/Mp4FrameWriter.kt` | HEVC access units → .mp4, including NAL parsing for `csd-0` |
| `glasses/GlassesManager.kt` | Connection and worn state, without opening a session |
| `notify/Notifications.kt` | The prompt, and the Wear actions |
| `notify/PromptActionReceiver.kt` | Record / snooze, executed on the phone |
| `schedule/DailyScheduler.kt` | Picks and arms the random daily moment, re-rolls, countdown deadline |
| `schedule/Receivers.kt` | Samples the glasses at the moment, prompts or re-rolls |
| `service/RecordingService.kt` | Runs the capture, writes the clip, updates the streak |
| `montage/MontageBuilder.kt` | Concatenates a month without re-encoding |

## Known limits

- **Audio is not captured.** DAT's in-stream audio is available only on the development
  and beta release channels and the API may still change, so clips are video-only. The
  hook for it is `StreamConfiguration(audioCodec = …)` in `ClipRecorder`.
- **Resolution is bandwidth-bound.** The SDK drops resolution, then frame rate, to fit
  the Bluetooth Classic link. Asking for `HIGH` often looks worse than `MEDIUM`.
- **Exact alarms.** The app declares `USE_EXACT_ALARM`. That is fine for personal use but
  Google Play restricts the permission, so a Play release would need
  `SCHEDULE_EXACT_ALARM` with the user-facing opt-in instead.
- **Watch vibration is the watch's call.** The phone channel uses a long three-pulse
  pattern, but Wear OS re-renders a bridged notification with its own haptics driven by
  the channel's *importance* rather than replaying the phone's pattern. High importance
  is what makes the watch buzz hard; the pattern itself is what you feel in a pocket.

## Troubleshooting

**`KspDebugKotlin` fails with `FileNotFoundException … DayLogDao_Impl.kt`**

Stale incremental state from the `com.threeseconds` → `de.freal.threeseconds` rename:
KSP still has the old package in its caches and tries to write into a directory that no
longer exists. One clean fixes it for good.

```bash
./gradlew clean
```

**Notification changes do not take effect**

Android freezes a channel's importance, sound and vibration at creation. Editing
`ensureChannels` is not enough for an install that already has the channel — bump the
version suffix on `Notifications.CHANNEL_PROMPT` and add the old id to
`LEGACY_PROMPT_CHANNELS`, which deletes it on next launch.
