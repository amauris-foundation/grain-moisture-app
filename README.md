# Grain Moisture App

A low-cost grain moisture meter made from a phone, a USB-C headset adapter, a tin can,
a bolt and a few 3D-printed parts. Built by the Amauris Foundation for smallholder
farmers in Ghana drying rice paddy, maize and other grains.

## Install
On the Android phone, open the latest release and download `grain-moisture.apk`:

**https://github.com/amauris-foundation/grain-moisture-app/releases/latest/download/grain-moisture.apk**

Android will ask once to allow installs from your browser. After that, the app checks
for new versions when it opens and offers to install them.

## Using it (bench test)
1. **Setup:** plug in the adapter and cell cable, tap *Test signal*. You want the headset mic
   in use, signal/noise above 20 dB and no clipping.
2. **Measure:** start live, adjust tone level and phone volume until the level sits between
   about −40 and −6 dB. Keep those settings for all samples.
3. **Sweep:** for each sample, fill and strike off the cell, run the sweep, empty, refill,
   repeat (3 refills per sample).
4. **Calibrate:** enter each sample's true moisture (oven dry or commercial meter). The plot
   shows signal level against moisture with a best-fit line.
5. **Data:** save or share the CSV.

See `CLAUDE.md` for the design notes, wiring and build details.
