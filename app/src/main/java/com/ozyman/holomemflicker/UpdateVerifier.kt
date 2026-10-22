package com.ozyman.holomemflicker

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signed updates. A release is only trusted if update/release.json carries a valid
 * signature (update/release.json.sig) from the owner's offline release key
 * (holomem-flicker-update-key.pem, used only by this app - since v16; v15 and earlier trusted the lofi-room key). Only the PUBLIC half lives here; a hijacked
 * GitHub account can publish files but can't sign them.
 */
object UpdateVerifier {
    /** Public half of the release key (tools/update_key.pub). */
    private const val PUBLIC_KEY_B64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAkcqqOsKDCTrNJQxJDYXFXgj0KSgBKHzWpZo+wKD3ViNmpwuJ2w9kkjAp869qDjCN504fHlY1GO4r5tw4l/IuEhXOUA1B0GA/8NBbfZHk7/gih01wFUzAdaQy54FAST0egWk9RLm/TFYFyaVnUogQ2l9lbKAA45iWosGY3LXmgEFd3+kAsLKm8FcypLIXLzP7mfIYhkv60DxXYcgIRmYrj7wKlxjJw9pYdFrEIouwuGOHOdTnd6RNbkHvuSS2yb/GlBSNR6e+YKyiVAhX6flb8vHT9EEulyxkz4m16lZBaSbSrm6xEzpRM7BSMLJxbScR9PuPrgFn3VjG8fjxTHNK4WBnAWRy8+stuIHMmO1Z/m0yeSPHZCBkrGSXGHfrfCaI4u7EzGJmtU9JE/u+hpP8lkGqSzDgU2iN8v/zoHeoPyz5By+IAQcWGtv0rS+xyzUeE7R+p2WwkLuB8JNxfY2pSkB+N5593AqqPwGXK/a+OVX3V6tvLi+FqfoWV3hGQj//AgMBAAE="

    const val MANIFEST_URL = "https://raw.githubusercontent.com/Ozyman515/holomem-flicker/main/update/release.json"
    const val SIG_URL = "https://raw.githubusercontent.com/Ozyman515/holomem-flicker/main/update/release.json.sig"

    class Release(val version: Int, val apkUrl: String, val sha256: String)

    /** RSA PKCS#1 v1.5 / SHA-256 over the exact manifest bytes (what Godot's Crypto.sign makes). */
    fun signatureOk(manifest: ByteArray, sigText: String): Boolean = try {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val sig = Base64.decode(sigText.trim(), Base64.DEFAULT)
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(manifest)
            verify(sig)
        }
    } catch (_: Throwable) {
        false
    }

    /** Parse a manifest that has ALREADY passed [signatureOk]. */
    fun parse(manifest: ByteArray): Release? = try {
        val o = JSONObject(String(manifest, Charsets.UTF_8))
        val url = o.getString("apk")
        val sha = o.getString("sha256").lowercase()
        // only our own GitHub release downloads, and a well-formed hash
        if (!url.startsWith("https://github.com/Ozyman515/holomem-flicker/releases/download/")) null
        else if (!Regex("^[0-9a-f]{64}$").matches(sha)) null
        else Release(o.getInt("version"), url, sha)
    } catch (_: Throwable) {
        null
    }

    fun sha256Hex(f: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
