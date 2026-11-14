# Atomic Clock

An Android clock that shows true time to the millisecond, measured against an internet
time server (`time.google.com`), and can set the phone's own clock to match it.

**[Download the latest APK](https://github.com/Unpiloted0852/AtomicClock/releases/latest)**

## What it does

- Shows the true time (hours, minutes, seconds, milliseconds) and how far the phone's
  clock is off, with the measurement margin.
- **Set system clock** corrects the phone's clock to within a few milliseconds, at the
  push of a button. See below for the one-time setup.
- Optional audio tick every second, with a longer tone on the minute.
- Up to six world clocks (any of about 70,000 cities, or UTC), each small, medium or
  large, shown as a list or a grid. Add them from the ⋮ menu; long-press one on the main
  screen and drag it to move it above or below the main clock.
- Checks this repository's releases at launch and offers to install a newer version.

## Why the phone's clock is off at all

Android only corrects its clock when it is 2 seconds or more wrong, and checks internet
time only about once every 18 hours. Phones that also take time from the cell network
get it in whole seconds, often late. So a phone's clock can sit up to about 2 seconds off
indefinitely.

## Setting the system clock

A normal app is not allowed to change the clock. The **Set system clock** button gets
round that by connecting to the phone's own debugging service (the one a USB cable uses)
and running the time commands with its rights. To get past Android's 2-second rule it
moves the clock 3 seconds away from the true time and then to the true time, so the clock
is wrong by 3 seconds for a fraction of a second.

The first press of **Set system clock** walks you through a one-time setup on the phone
itself. It takes about a minute and needs no computer:

1. Connect to Wi-Fi.
2. Turn on **Developer options** (Settings › About phone › tap *Build number* 7 times).
3. In Developer options, turn on **Wireless debugging**, open it, and tap
   **Pair device with pairing code**.
4. Keeping that box open, pull down the Atomic Clock notification, tap **Enter code**
   and type the 6-digit code.

The app then pairs with the phone's debugging service and uses it once to give itself
permission to switch Wireless debugging on and off. From then on each press turns
Wireless debugging on for a few seconds, sets the clock, and turns it off again. It
needs Wi-Fi, because Wireless debugging only runs on Wi-Fi.

"Set time automatically" must stay on: the app sets the clock through Android's
automatic time, which Android ignores when that setting is off.

Removing *AtomicClockApp* from Wireless debugging's paired devices, or revoking USB
debugging authorisations, undoes the pairing; the next press offers the setup again.

`tools/sync-phone-clock.sh` does the same from a computer over USB, with no setup.

### Optional: stop cell-network time from overriding it

```bash
adb shell device_config put system_time time_detector_origin_priorities_override network
```

The app sets this itself on every press. Undo it with
`adb shell device_config delete system_time time_detector_origin_priorities_override`.
Automatic time zone detection is not affected.

## Things worth knowing

- Accuracy is limited by the network path to the time server: typically within 5–20 ms.
- The clock still drifts afterwards, roughly 0.1 s a day on a typical phone.
- Updating from a build signed with a different key (such as a debug build you built
  yourself) needs an uninstall first, which also undoes the one-time setup above.

## Building

Open in Android Studio, or `./gradlew assembleDebug`. Release signing is read from an
untracked `keystore.properties` in the project root (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); without it the release build is left unsigned.

The ADB connection uses [libadb-android](https://github.com/MuntashirAkon/libadb-android).

City data © [GeoNames](https://www.geonames.org/), CC BY 4.0. `tools/make-cities.py`
rebuilds `app/src/main/assets/cities.bin` from their `cities5000` and `admin1CodesASCII`
files.
