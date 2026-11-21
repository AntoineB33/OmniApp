package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.E2ee
import org.example.project.scheduler.sync.E2eeException

/**
 * User rule 2026-11-21: what the server stores, it cannot read (`docs/invariants/sync-and-accounts.md` § *End-to-end
 * encryption*). The primitives, on every platform's own crypto (JDK, WebCrypto, OpenSSL).
 */
class E2eeTest {
    @Test
    fun a_value_opens_with_its_key_where_it_was_stored_and_nowhere_else() = runTest {
        val key = E2ee.ContentKey.random()
        val text = """{"id":"task/1","title":"Write the cover letter"}"""
        val sealed = E2ee.encrypt(key, text, "tasks|task/1")
        assertTrue(E2ee.isEncrypted(sealed))
        assertFalse("cover letter" in sealed, "the server sees no plaintext")
        assertEquals(text, E2ee.decrypt(key, sealed, "tasks|task/1"))
        // Moved to another row by the server: refused.
        assertFailsWith<E2eeException> { E2ee.decrypt(key, sealed, "tasks|task/2") }
        // Another account's key: refused.
        assertFailsWith<E2eeException> { E2ee.decrypt(E2ee.ContentKey.random(), sealed, "tasks|task/1") }
        // Tampered: refused.
        val flipped = sealed.dropLast(3) + (if (sealed[sealed.length - 3] == 'A') "B" else "A") + sealed.takeLast(2)
        assertFailsWith<E2eeException> { E2ee.decrypt(key, flipped, "tasks|task/1") }
        // A fresh nonce each time: the same value twice is two different ciphertexts.
        assertNotEquals(sealed, E2ee.encrypt(key, text, "tasks|task/1"))
    }

    @Test
    fun the_password_gives_a_login_secret_that_is_not_the_password_and_a_key_that_wraps_the_content_key() = runTest {
        val keys = E2ee.passwordKeys("Me@Example.com ", "correct horse", iterations = 1_000)
        val again = E2ee.passwordKeys("me@example.com", "correct horse", iterations = 1_000)
        assertEquals(keys.loginSecret, again.loginSecret, "the same email (any case) and password give the same secret on every device")
        assertFalse("correct horse" in keys.loginSecret)
        assertNotEquals(keys.loginSecret, E2ee.passwordKeys("me@example.com", "wrong horse", iterations = 1_000).loginSecret)

        val content = E2ee.ContentKey.random()
        val wrapped = E2ee.wrap(content, keys, "user-1")
        assertEquals(content.encoded(), E2ee.unwrap(wrapped, again, "user-1")?.encoded())
        assertNull(E2ee.unwrap(wrapped, E2ee.passwordKeys("me@example.com", "wrong horse", iterations = 1_000), "user-1"))
        assertNull(E2ee.unwrap(wrapped, keys, "user-2"), "a key wrapped for one account does not open as another's")
        assertEquals(content.encoded(), E2ee.ContentKey.decoded(content.encoded()).encoded())
    }

    @Test
    fun the_production_derivation_is_stable() = runTest {
        // Pinned: a change here locks every account out of its server copy. `scripts/internal/account_db_admin.py`
        // derives the same secret.
        val keys = E2ee.passwordKeys("a@b.c", "pw")
        assertEquals(LOGIN_SECRET_A_B_C_PW, keys.loginSecret)
    }

    @Test
    fun the_fallback_pbkdf2_gives_the_same_bytes_as_the_platform_one() = runTest {
        // Android before API 26 has no PBKDF2-HMAC-SHA256 provider: the fallback must derive the very same master.
        val salt = "omniapp-e2ee-v1|a@b.c".encodeToByteArray()
        val viaHmac = E2ee.pbkdf2FromHmac("pw".encodeToByteArray(), salt, 2_000)
        val viaProvider = dev.whyoleg.cryptography.CryptographyProvider.Default.get(dev.whyoleg.cryptography.algorithms.PBKDF2)
            .secretDerivation(dev.whyoleg.cryptography.algorithms.SHA256, 2_000, with(dev.whyoleg.cryptography.BinarySize) { 256.bits }, salt)
            .deriveSecretToByteArray("pw".encodeToByteArray())
        assertEquals(viaProvider.toList(), viaHmac.toList())
    }

    private companion object {
        const val LOGIN_SECRET_A_B_C_PW = "yURi1e/Wf200APbTlfpVhQSCREfUf99xm/++aW7otnI="
    }
}
