# Holomem Flicker

Controller-to-touch mapper for hololive Dreams on Android (Legion Tab + Razer Kishi V3 Pro).

- **Tap points**: holding a button = a finger held at that spot. Any button can have several points (one press, several fingers).
- **Flicks**: each stick works like a steering wheel. Pushing it puts a finger down at that side's flick point and drags it outward, which the game reads as a flick. Pushing sideways shifts where the flick starts ("edge reach").
- Everything runs as one multi-touch stream, so holds, taps and flicks can overlap.
- **Battery**: nothing polls while the controller is idle. After a minute without input the flicker sleeps (overlay hidden, notification says "sleeping"); any button or stick push wakes it instantly, and that press still reaches the game.

Runs through [Shizuku](https://shizuku.rikka.app/), which gives it shell-level access to read the controller and inject touches. No root needed.

## Install
1. Install Shizuku from the Play Store and start it through **Wireless debugging** (redo this after each reboot).
2. Download the newest `holomem-flicker-vN.apk` from **Releases** and install it.
3. Open Holomem Flicker, tap **Grant Shizuku access**, then **Start flicker**.
4. Turn the Lenovo key mapper off (or use an empty profile) while playing songs.

Every push to `main` builds a new signed APK into Releases. Build logs go to the `ci-log` branch.
