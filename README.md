# Solar Guardian Local

An independent, local-only EPEVER BLE companion built from the offline device data bundled with the supplied Solar Guardian APK. It contains no sign-in screen and requests no Internet permission.

## Current features

- Scan for BLE devices and connect to the EPEVER dongle.
- Select an EPEVER controller model from the bundled offline catalog.
- Read common live values locally over BLE using Modbus RTU, with refresh every 10 seconds while live polling is on.
- View supported model-listed 16-bit settings. A write requires a value and a second confirmation; every change is sent directly to the controller.
- No cloud, account, remote access, or network calls are included.

This is a first local companion focused on BLE, live values, and basic settings; it is not yet a screen-for-screen replacement for every Solar Guardian feature. Charts, history, LoRa, cloud, and account features are not included. Only simple 16-bit settings are exposed, and values are written directly to the controller.

## Build the debug APK with GitHub Actions

1. Create a private GitHub repository.
2. Upload all files and folders from this project to the repository root. Include `.github/workflows/android-apk.yml` and `app/src/main/assets/epever.db`.
3. Commit the files to `main`.
4. Open **Actions** and choose **Build Solar Guardian Local APK**.
5. Download `SolarGuardianLocal-debug-apk` from a successful run, unzip it, then install `app-debug.apk` on the phone.

The APK is an unsigned-for-Play debug build and is intended for direct testing. Model support and BLE behavior must be confirmed with the actual controller. Use the correct model in the app before reading or changing settings. Avoid changing settings unless you know the value required by your installation.
