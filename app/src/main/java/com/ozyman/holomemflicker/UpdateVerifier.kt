package com.ozyman.holomemflicker

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signed updates. A release is only trusted if update/release.json carries a valid
 * signature (update/release.json.sig) from the owner's offline release key - the same
 * RSA key as the lofi-room project. Only the PUBLIC half lives here; a hijacked
 * GitHub account can publish files but can't sign them.
 */
object UpdateVerifier {
    /** Public half of the release key (tools/update_key.pub). */
    private const val PUBLIC_KEY_B64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAoI1P08pxAVK/zjMDmTzQv+f/tbnXcYnq3Mbn5951idRDo+QJ1CjG/hwdodYdYoF6dPN9Pn1HyS/RYn9n37KWMShkGPv3aaU/0Cq3zAKKC3btCRqfpN2fiH5moIq9zyhmaF1oWtu9SsTKny6O6H1zdONbUVja+v4ZS5lXkkI7Sjn5Bs6rwA2sTk7lyVT9cqYCH8OsW8Uc9Ag+OsNKw2XFdEx78nIRuw3sYVEFa/i+fp3xE0bRncsMnT8FiMNTuF+JFs8X/wKcMhSZk8rpKQJfPGZ0AlBHrEvxBuiVU0yKcC8ZlRaNycq6rpxEUXOozboTBdScpbhAi8UwkFmTcRg7yVIxSquX0Ooyq4nAkkfBVr4rb6dKNlyY8LsqeadmaHZ4kw3Hcnnl7WiCO4LSDxt+kYVPi11czJ9Htna39Jo54+2wYiVjsjlCBvGACofPFQd+0XY6XtzN26dU4RI5W6svMkB1oR2e5c4Jcvf/k6C/R4Wj/7C+tPjnxOaLVGt3AUkvAgMBAAE="

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
