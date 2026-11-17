# Grain Moisture App — project notes

Android app (Amauris Foundation) that turns a phone plus a tin-can sensing cell into a
low-cost grain moisture meter for smallholder farmers in Ghana. Read this before changing anything.

## How the sensor works
- **Cell:** a standard 400 g / 15 oz food can (outer electrode, grounded) with a metal bolt
  standing in the centre (sense electrode). 3D-printed base (hex pocket for the bolt head,
  bolt never touches the can) and top collar keep the bolt centred. Grain fills the ~2.5–3 cm
  ring between bolt and can. Fill through a hopper from a fixed height and strike off at the
  rim; never press the grain (packing changes the reading).
- **Circuit (voltage divider):** phone tone → series resistor R (start ~10 kΩ) → bolt. The mic
  input also connects to the bolt and reads the voltage left on it. Wetter grain conducts and
  stores more charge, so it leaks more of the tone to the grounded can and the mic level drops.
  Grounding the can also shields the bolt from hands.
- **Plug pinout (CTIA, Samsung and Apple):** Tip = left audio = tone out (through R).
  Ring 1 = right audio, unused. Ring 2 = ground → can. Sleeve = mic → bolt.
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
  listens on the mic. User touches bare wires in pairs: left tone heard = mic + tip, right tone =
  mic + ring 1, headset button press = mic + ground. Background is learned for ~2 s with the
  wires apart; a tone counts as heard 20 dB above background. Right channel is silent otherwise.
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
