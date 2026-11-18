# Grain Moisture App — project notes

Android app (Amauris Foundation) that turns a phone plus a tin-can sensing cell into a
low-cost grain moisture meter for smallholder farmers in Ghana. Read this before changing anything.

## How the sensor works
- **Cell:** a standard 400 g / 15 oz food can (outer electrode, driven by the tone) with a metal bolt
  standing in the centre (sense electrode). 3D-printed base (hex pocket for the bolt head,
  bolt never touches the can) and top collar keep the bolt centred. Grain fills the ~2.5–3 cm
  ring between bolt and can. Fill through a hopper from a fixed height and strike off at the
  rim; never press the grain (packing changes the reading).
- **Circuit (current design, NO resistor — decided 2026-11-17 because resistors aren't easy
  to get in the field):** tone → can → paddy → bolt → mic. The phone's internal mic-bias resistor
  (~2 kΩ) and the paddy form the voltage divider; the signal returns through the adapter's
  internal ground, so no external ground wire. Wetter paddy conducts and stores more charge, so
  the mic level RISES with moisture. Dry paddy acts mostly like a small capacitor, so higher
  frequencies pass more signal in principle, BUT the A17's USB headset mic path (VOICE_RECOGNITION;
  UNPROCESSED isn't offered for it) cuts off high frequencies: in a 2026-11-17 bench video the
  10 kHz bin sat at about −130 dB even with damp rice while broadband hiss was about −67 dB, and
  the "spikes" were contact clicks, not the tone. Default tone is now 1 kHz; the sweep covers
  200 Hz–10 kHz; the Setup test estimates the usable range from the mic hiss (tone off) at
  1–15 kHz. Tone is on the can because the phone's output is low impedance
  (hands on the can barely matter); the sensitive mic node stays inside on the bolt.
- **Plug pinout (CTIA, Samsung and Apple):** Tip = left audio = tone → can. Sleeve = mic → bolt.
  Ring 1 (right audio) and Ring 2 (ground) not connected at the cell; insulate them. Ground
  touching the can would short the tone output.
- Earlier alternative (not used): tone → ~10 kΩ resistor → bolt, mic on bolt, can grounded; there
  the level FALLS with moisture.
- Phone mic lines carry a ~2 V DC bias; very low resistance (very wet grain) could look like a
  headset button press. The app ignores headset buttons while measuring.

## App (v1 = bench-test tool)
- Plain Android framework + Kotlin, **no AndroidX or other dependencies** (keeps the build simple).
- Tabs: Setup (wire finder, device/mic checks, signal test), Measure (live level, record a reading),
  Sweep (frequency sweep saved per sample and refill), Calibrate (enter reference moisture,
  plot level vs moisture with linear fit), Data (CSV export to Downloads / share).
- Audio: 48 kHz, tone on left channel only, mic recorded with `UNPROCESSED` source when
  supported (else `VOICE_RECOGNITION`), routed to the USB/wired headset. Level is measured
  with a Hann-windowed single-frequency DFT in 100 ms blocks (`AudioEngine.toneAmplitude`).
- **Wire finder** (Setup tab): plays 700 Hz on left (tip) and 2300 Hz on right (ring 1) and
  listens on the mic. Every test pair must include the mic wire (the app can only hear the mic).
  Strong tone (≥35 dB over background, or clipping) = direct contact: left = tip, right = ring 1.
  Faint tone (15–35 dB) = leakage through the shared ground, not a real detection. Headset button
  press = mic + ground, unless a strong tone was seen within 1.5 s (a speaker wire shorted straight
  to the mic also trips the button detector). Results are held ~2 s so flickery contacts read clearly.
  Tone level 0.1 to avoid overload. Right channel is silent outside the wire finder.
- Apple EarPods colours (confirmed by Aaron): green = tip, red = ring 1, white = mic, copper = ground
  (the copper wrapped around the white is the mic shield; all coppers join at ring 2). Cell wiring:
  green → can, white → bolt, coppers + red taped off.
- A dead short on the mic held too long opens the voice assistant (Gemini); a ~240 Ω path also
  maps to the voice-assist key. VOICE_ASSIST/ASSIST keys are swallowed while audio runs, but
  Android may intercept a long headset-hook press system-wide before the app sees it.
- The mic's ~2 V DC bias now sees the paddy's DC resistance to the tone output. Paddy is far above
  the headset-button range (<~600 Ω), so false button presses should only happen with a short
  (bolt touching the can).
- **Diagnostics** (Measure tab, during live): shows the routed mic, broadband level and a count
  of headset-button signals, and logs time-stamped events: mic switched, input went SILENT
  (broadband < −95 dBFS, i.e. the adapter muted the mic), input came back, button signals, inputs
  added/removed. Added after a bench test where a damp sponge read −40 dB for a few seconds and
  then dropped to ~−100 dB (suspected adapter mic detection muting the input; also a very low
  resistance and a galvanic zinc/tin cell). Proposed hardware fix: splice the EarPods' own mic
  capsule back onto the white/copper so the adapter always detects a mic.
- Phone media volume is set and held during readings; volume and media keys are swallowed and a
  MediaSession absorbs headset button presses.
- Data: `readings.csv` and `calibration.csv` in app-private storage (`Store.kt`).
- Grain type is a field on every reading so calibration can be per crop.

## Build and release
- GitHub Actions (`.github/workflows/build.yml`) builds on every push to `main` with Gradle
  8.11.1, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26.
- versionCode = GitHub run number; release tag `v1.0.<run>`; asset `grain-moisture.apk`.
- The app checks `releases/latest` on launch and installs updates with PackageInstaller.
- **Prototype signing:** `app/release.keystore` is committed (password in `app/build.gradle.kts`)
  so every CI build can update the installed app. Move it to a GitHub secret before any wider release.
- No local Android SDK is needed; CI is the compiler. Check the Actions log after each push.

## Calibration plan (field)
- Units are calibrated as built: 3–5 samples from the drying tarp, each read on a commercial
  meter (e.g. Sesi GrainMate) and then in the phone cell; app fits the curve. Monthly re-check;
  recalibrate if off by more than ~1 % moisture.
- Fallback: salt-jar test (dry salt stays loose below ~15 %) to make a sealed dry reference sample.
- Targets: ~14 % for rice paddy milling/short storage; 13–14 % for long storage; maize ≤13 %
  (aflatoxin risk rises sharply above that).

## Roadmap
- v1 (current): bench test on Samsung Galaxy A17 (SM-A176U, Android 16).
- v2: farmer mode (one button, "keep drying" / "safe to store"), reference-jar comparison,
  per-phone profiles, in-app build guide with pictures.
- Later: optional phase measurement, temperature correction, direct USB-C analog-mode plug.
