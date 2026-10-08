# Voice Camera (Android)

Hands-free camera: open the app and say what you want. Kotlin + CameraX, Android 10+ (API 29), no accounts or keys.

## The screen (laid out like a normal camera app)
- **Top:** ⚙ settings (left), the recording timer (centre, while recording), and the voice icon (right: **gold** = listening, white = paused, grey with a slash = off; tap to turn voice on/off).
- **Just under the top bar:** what the camera is doing ("Starting in 3…", "Saved a 0:30 video…") and what it heard.
- **Bottom, top to bottom:** the **zoom pills** (0.5 · 1× · 2 · 3 · 5 · 10, only the steps your camera can do; the highlighted one shows the exact zoom), the **VIDEO | PHOTO** switch, then
  the **gallery thumbnail** with the number of takes (tap to review), the big round **shutter**, and the **flip camera** button.
- **Shutter:** red dot = tap to record, white = tap to take a photo (switch with VIDEO | PHOTO). It starts the same way as saying
  "record video" / "take a photo": it waits and records by the defaults in settings. While waiting or recording it becomes a **red square**; tap it to cancel or stop.
- Pinch the picture to zoom as well. The shutter and buttons are dimmed while the camera is busy.

## What you can say

| Say | What happens |
|---|---|
| **"record video after 5 seconds till 30 seconds"** | waits 5 s (with a big on-screen countdown and beeps), records, and **stops by itself after 30 s** |
| "use the front camera" / "back camera" / "selfie camera" | switches camera |
| "switch camera" / "flip the camera" | toggles between front and back |
| "front camera and record for 10 seconds" | camera + recording in one sentence |
| "start recording after 3 seconds and stop after 20 seconds" | 3 s wait, 20 s video |
| "zoom in" / "zoom out" | one step closer / wider (x1.5 each) |
| "zoom to 3" / "zoom 2.5x" / "zoom three times" | exact magnification (limited to what the lens supports) |
| "maximum zoom" / "reset zoom" | fully zoomed in / back to 1x |
| "zoom to 2 and record after 5 seconds till 30 seconds" | zoom, wait, record in one sentence |
| "take a photo after 3 seconds" | countdown, then a photo |
| "stop" / "cancel" | cancels a countdown (tapping the red-square shutter does the same, and also ends a recording early) |

Numbers can be spoken or digits ("five", "thirty", "1 minute", "half a minute").
**How the timing words are read:** `after / in / wait / delay N` = how long to wait before starting;
`for / till / until / to / of N` (or `stop after N`) = how long the recording lasts.
With no length given it records 30 seconds. Limits: wait up to 5 min, recording 1 s to 10 min.

**Zoom by hand too:** pinch on the picture with two fingers, or tap a **zoom pill** above the shutter.
Pinch and the pills also work *while recording*; voice
commands don't (the microphone is busy recording). Each camera keeps to its own limits (e.g. 0.5x to 10x on a
Galaxy Flip5 back camera), and zoom returns to 1x when you switch cameras.

## Your own defaults (the ⚙ icon)
Tap **⚙** at the top left of the camera screen to choose how long to **wait before starting** (used for videos and photos; 0 = right away) and
how long a video **records**. They are used whenever a sentence has no time in it, so "record video" alone waits and records by
your defaults, and "take a photo" waits your default wait. Numbers you say always win ("record after 5 seconds till 30 seconds").
You can also set them by voice: "set default wait to 5 seconds", "set default record time to 45 seconds",
and skip the wait once with "record now" or "no wait". Standard defaults: no wait, 30 seconds.

## Take several, then review, love, delete, upload
After a video or a photo is saved the app **stays on the camera and keeps listening**, so you can take as many as you like.
The **thumbnail at the bottom left** shows the newest one and how many you have; tap it (or say "show my videos" / "play my last video") to review them all.
On the review screen you can:
- **Play / Pause / Replay** a video (with the usual scrub bar), or look at a photo full size,
- tap **Love** (or the heart on its row) to mark the ones you like,
- **Delete** an item (asks to confirm),
- **Record another** to go back to the camera,
- **Upload** the one you pick: opens Android's share sheet (WhatsApp, Drive, YouTube, e-mail, ...),
- **Delete all except ❤** to clear everything unloved in one go (asks to confirm, and is disabled until at least one item is marked ❤).

Hearts are remembered by the app; the videos and photos themselves are ordinary files in the Gallery
(Movies/VoiceCamera and Pictures/VoiceCamera).
Note: Android only lets the app see (and delete) videos it recorded itself; clips from an earlier install of the app are not listed.

Videos are saved to **Movies/VoiceCamera** and photos to **Pictures/VoiceCamera** (visible in the Gallery).

## How it behaves
- It starts listening as soon as the app is open and permissions are granted (the voice icon at the top right shows the state; tap it to turn voice control off/on).
- While a countdown, recording or photo is running it **stops listening** (the video needs the microphone) and resumes automatically afterwards.
- Leaving the app (Home button) ends a running recording and saves it.
- Voice needs Android's speech service (the Google app) and usually an internet connection. If it isn't available the app says so; the camera screen still works with the on-screen controls (shutter, zoom, flip, gallery, settings).
- **Landscape:** turn the phone sideways and the controls move to the edges like a normal camera app: settings and voice on the left, status at the top,
  zoom along the bottom, and flip / shutter / gallery down the right edge with VIDEO | PHOTO beside them. Turning the phone does not interrupt a recording.

## Run it
1. Android Studio: **File > Open** this folder, let Gradle sync, press Run. Or from a terminal:
   `gradlew assembleDebug` (APK: `app/build/outputs/apk/debug/app-debug.apk`) and `adb install -r` it.
2. Allow **Camera** and **Microphone** when asked.

`gradlew testDebugUnitTest` runs the 49 tests that check how spoken sentences are understood (`CommandParserTest`).

## Notes
- Speech recognition is Android's built-in recogniser (Google speech service), set to English (US); it usually needs an internet connection. Some phones play a small "ding" each time listening restarts.
- Code map: `CommandParser.kt` (sentence -> command, pure Kotlin), `VoiceListener.kt` (speech recogniser with safe restarts), `MainActivity.kt` (CameraX, countdown, recording, UI).
- Package name is `com.example.voicecamera`; change it (and the folder under `java/`) before publishing to Google Play. Store listing files and the checklist are in `store/` (see `store/PLAY_STORE_GUIDE.md`).
- Built with: AGP 8.5.2, Gradle 8.7, Kotlin 2.0.0, CameraX 1.4.2, compileSdk 35 / targetSdk 34.
