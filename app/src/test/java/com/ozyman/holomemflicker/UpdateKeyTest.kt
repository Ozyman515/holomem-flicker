package com.ozyman.holomemflicker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The key built into the app is the flicker's own update key (fixture signed with holomem-flicker-update-key.pem). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateKeyTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun acceptsManifestSignedWithTheFlickerKey() {
        assertTrue(UpdateVerifier.signatureOk(res("test-release.json"), String(res("test-release.json.sig"))))
    }

    @Test
    fun rejectsATamperedManifest() {
        val m = res("test-release.json").clone()
        m[m.indexOf('1'.code.toByte())] = '9'.code.toByte() // version 1 -> 9
        assertFalse(UpdateVerifier.signatureOk(m, String(res("test-release.json.sig"))))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(UpdateVerifier.signatureOk(res("test-release.json"), "not a signature"))
    }
}
