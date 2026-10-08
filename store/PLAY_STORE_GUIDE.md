# Voice Camera: Google Play listing and checklist

## Files in this folder (ready to upload)
| Play Console field | File | Spec check |
|---|---|---|
| App icon | `icon/play_icon_512.png` | 512 x 512, 32-bit PNG, ~230 KB (limit 1 MB) |
| Feature graphic | `feature_graphic_1024x500.png` | 1024 x 500, 24-bit PNG, no transparency |
| Phone screenshots (2 to 8) | `play_phone_screenshots/01_...07_*.png` | 1080 x 1920 (9:16), 24-bit PNG, no transparency |
| Source artwork | `source/` | your original icon (png) and feature graphic (webp) |

The screenshots are real captures from a Galaxy Z Flip5 (app area only, status bar removed), with a caption added by `tools/make_store_assets.py` (the icon and feature graphic are your supplied artwork, not generated).
The raw 1080 x 2640 captures are not committed (Play rejects screens taller than 2:1).
Tablet (7" / 10") screenshots are optional and not included.

## Suggested listing text

**App name (max 30):** Voice Camera

**Short description (max 80):**
Hands-free camera: say “record video after 5 seconds” and it does the rest.

**Full description (max 4000):**
Voice Camera is a camera you can talk to. Open it, say what you want, and step back.

SAY IT, IT RECORDS
• “Record video after 5 seconds till 30 seconds”: waits 5 seconds, records, and stops by itself at 30.
• “Use the front camera”, “back camera”, “switch camera”.
• “Take a photo after 3 seconds”.
• “Zoom in”, “zoom to 3”, “maximum zoom”, “reset zoom”.
• “Stop” to cancel or end early. Say “record now” to skip the wait.

A REAL CAMERA SCREEN TOO
• Big shutter, VIDEO and PHOTO modes, front/back flip, pinch zoom and zoom steps (0.5x to 10x on phones that support it).
• A large on-screen countdown with beeps, so you can step back and get ready.
• Works in portrait and landscape; turning the phone never interrupts a recording.

TAKE SEVERAL, KEEP THE BEST
• Stay on the camera and keep taking videos and photos by voice.
• Review them all in one place: play your videos, trim a video to just the part you want (saved as a new copy), mark your favorites with a heart, delete the rest in one tap, and share the one you pick.

YOUR DEFAULTS
• Choose your usual wait before starting and your usual recording length once; “record video” then uses them. Anything you say out loud still wins.

PRIVACY
• Your videos and photos stay on your phone in Movies/VoiceCamera and Pictures/VoiceCamera. The app has no accounts and does not upload anything by itself.
• Voice commands use Android's built-in speech recognition (provided by Google), which may need an internet connection.

**Category:** Photography (or Video Players & Editors)
**Tags / keywords:** voice camera, hands-free, timer camera, self timer, video recorder

## BEFORE YOU CAN PUBLISH (these are not done yet)
1. ~~Change the package name~~ **Done:** the app id is now `com.plotfarms.voicecamera` (it can never be changed after the first upload).
2. ~~Raise the target API level~~ **Done:** compileSdk and targetSdk are 36, with edge-to-edge insets handled on all three screens.
3. **Create a signed release bundle (.aab)** with your own upload key. Keep the keystore file and its passwords safe: losing them makes updates very difficult. Turn on **Play App Signing** when you upload.
4. **Privacy policy URL**: the page is written (`docs/privacy.html`). Fill in the two highlighted spots (developer name, contact email), then publish it at a public address (e.g. GitHub Pages: Settings > Pages > main /docs, giving https://sudhakarrichardson-cmd.github.io/VoiceCamera/privacy.html) and paste that URL in Play Console. Original note: (required because the app uses the camera and microphone). It can be a page on plotfarms.com. Say: no accounts, no data collected or sent by the app, media stays on the device, speech recognition is done by Android's Google speech service.
5. **Data safety form:** the app itself collects nothing. Mention that voice recognition is handled by Google's on-device/online speech service, and that nothing is shared or sold.
6. **Content rating questionnaire** (answers: no violence, no user-generated content shared, etc.).
7. **Personal developer accounts** created after Nov 2023 must run a closed test with at least 12 testers for 14 days before Production access. Check your account type in Play Console.
8. Optional: tablet screenshots, a short promo video, translations.
