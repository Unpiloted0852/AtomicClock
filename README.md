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

One-time setup, from a computer with `adb` and the phone connected over USB:

1. Turn on **Developer options** and **USB debugging** on the phone.
2. Let the app switch Wireless debugging on and off by itself:

   ```bash
   adb shell pm grant com.example.atomicclockapp android.permission.WRITE_SECURE_SETTINGS
   ```

3. Pair the app with the phone's debugging service:

   ```bash
   adb tcpip 5555
   ```

   Then press **Set system clock** in the app. Android asks *Allow USB debugging?*
   (the "computer" is the app itself): tick **Always allow** and tap **Allow**.
   Afterwards close the port again with `adb usb`.

From then on the button works without a computer. It needs Wi-Fi, because it uses
Wireless debugging, which it switches off again when done. Revoking USB debugging
authorisations in Developer options undoes the pairing.

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
- Updating from an older build signed with a different key (such as a debug build) needs
  an uninstall first, which also undoes the one-time setup above.

## Building

Open in Android Studio, or `./gradlew assembleDebug`. Release signing is read from an
untracked `keystore.properties` in the project root (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); without it the release build is left unsigned.

The ADB connection uses [libadb-android](https://github.com/MuntashirAkon/libadb-android).
