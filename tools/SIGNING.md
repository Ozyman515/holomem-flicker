# Signed updates

The app only installs a release if `update/release.json` is signed by the owner's offline key
(`holomem-flicker-update-key.pem`, used only by this app; until v17 it shared the lofi-room key). The public half is `tools/update_key.pub` and is
compiled into the app (`UpdateVerifier.kt`). The private key is never committed.

## Releasing
1. Test changes on a `screens/...` branch (screenshots + unit tests, no version change). Merge to `main` freely.
   To publish an update, raise the number in `version.txt` by one and push it to `main`: only then does
   GitHub Actions build `holomem-flicker-vN.apk` and attach `release.json` (version, APK link, SHA-256)
   to the vN release. Nothing is offered to the app yet.
2. Download that `release.json` into `update/release.json`.
3. Sign it (Godot 4, same as lofi-room):
   `godot --headless -s tools/sign_release.gd -- /safe/place/holomem-flicker-update-key.pem update/release.json tools/update_key.pub`
   or with OpenSSL: `openssl dgst -sha256 -sign /safe/place/holomem-flicker-update-key.pem update/release.json | base64 -w0 > update/release.json.sig`
4. Commit `update/release.json` + `update/release.json.sig`. The app now offers vN.

A stolen GitHub account can build and upload APKs, but can't produce a signature, so the app refuses them.
The app also checks the downloaded APK against the signed SHA-256, and only accepts downloads from this repo's releases.

## Key change at v17
v17 is the hand-over release (v16 was built but never signed or offered): the app versions before it (v15 and earlier) only trust the old lofi-room key,
so **v17's manifest is signed with the lofi-room key** (check it against `git show 539a631:tools/update_key.pub`).
v17 itself trusts only the new flicker key, so **v18 onward is signed with `holomem-flicker-update-key.pem`**.

## APK signing key
CI signs the APK with the keystore in the `KEYSTORE_B64` / `KEYSTORE_PASS` repository secrets
(alias `release`). The owner keeps `release.jks` offline as a backup. The old public `app/flicker.jks`
was retired at v10 (one-time reinstall).
