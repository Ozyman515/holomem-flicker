# Holomem Flicker

*An unofficial fan-made project. Not affiliated with or endorsed by COVER Corporation, hololive production or QualiArts.*

Controller-to-touch mapper for hololive Dreams on Android (Legion Tab + Razer Kishi V3 Pro).

- **Tap points**: holding a button = a finger held at that spot. Any button can have several points (one press, several fingers).
- **Flicks**: each stick works like a steering wheel. Pushing it puts a finger down at that side's flick point and drags it outward, which the game reads as a flick. Pushing sideways shifts where the flick starts ("edge reach").
- Everything runs as one multi-touch stream, so holds, taps and flicks can overlap.
- **Precision d-pad**: both layout editors have on-screen arrows; each tap moves the selected point or stick 2 px (hold to repeat), and the middle button flips the pad to the other corner.
- **Disclaimer**: shown on first launch (must be accepted to use the app) and any time from the Disclaimer tab, top right of the main screen.
- **Battery**: nothing polls while the controller is idle. After a minute without input the flicker sleeps (overlay hidden, notification says "sleeping"); any button or stick push wakes it instantly, and that press still reaches the game.

Built with Claude (Anthropic's AI): the code, screens and disclaimer were written by Claude under the project owner's direction.

Runs through [Shizuku](https://shizuku.rikka.app/), which gives it shell-level access to read the controller and inject touches. No root needed.

## Install
1. Install Shizuku from the Play Store and start it through **Wireless debugging** (redo this after each reboot).
2. Download the newest `holomem-flicker-vN.apk` from **Releases** and install it.
3. Open Holomem Flicker, tap **Grant Shizuku access**, then **Start flicker**.
4. Turn the Lenovo key mapper off (or use an empty profile) while playing songs.

A new version is built into Releases only when `version.txt` is raised on `main`, and the app offers it once its manifest is signed (see `tools/SIGNING.md`). Build logs go to the `ci-log` branch.

## License
Copyright (C) 2026 Ozyman515

Holomem Flicker is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation.
It is distributed WITHOUT ANY WARRANTY; see the license and the in-app disclaimer for details.
