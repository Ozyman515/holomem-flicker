# Signed updates

The app only installs a release if `update/release.json` is signed by the owner's offline key
(the same RSA key as the lofi-room project). The public half is `tools/update_key.pub` and is
compiled into the app (`UpdateVerifier.kt`). The private key is never committed.

## Releasing
1. Push to `main`. GitHub Actions builds `holomem-flicker-vN.apk` and attaches `release.json`
   (version, APK link, SHA-256) to the vN release. Nothing is offered to the app yet.
2. Download that `release.json` into `update/release.json`.
3. Sign it (Godot 4, same as lofi-room):
   `godot --headless -s tools/sign_release.gd -- /safe/place/update_key.pem update/release.json tools/update_key.pub`
   or with OpenSSL: `openssl dgst -sha256 -sign /safe/place/update_key.pem update/release.json | base64 -w0 > update/release.json.sig`
4. Commit `update/release.json` + `update/release.json.sig`. The app now offers vN.

A stolen GitHub account can build and upload APKs, but can't produce a signature, so the app refuses them.
The app also checks the downloaded APK against the signed SHA-256, and only accepts downloads from this repo's releases.

## APK signing key
CI signs the APK with the keystore in the `KEYSTORE_B64` / `KEYSTORE_PASS` repository secrets
(falls back to the old public `app/flicker.jks` until those exist).
