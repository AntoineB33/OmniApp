package org.example.project.scheduler.sync

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **End-to-end encryption of what the account stores on the server** (user rule 2026-11-21: *"any data sent to the
 * internet is crypted — even Supabase can read user's data"*; `docs/invariants/sync-and-accounts.md` § *End-to-end
 * encryption*).
 *
 * The keys, all derived on the device:
 *  - **master** = PBKDF2-HMAC-SHA256(password, "omniapp-e2ee-v1|" + email, [PBKDF2_ITERATIONS]) — never leaves it;
 *  - **login secret** = HKDF(master, "omniapp auth v1"): what GoTrue is given as the password, so the server never sees
 *    the password itself and cannot derive the master from what it receives (HKDF is one-way);
 *  - **key-encryption key** = HKDF(master, "omniapp kek v1"): wraps the account's content key;
 *  - **content key**: 32 random bytes per account, made once by the device that first needs it, stored WRAPPED on the
 *    server (`account_key`) and in clear only in the device's local DB. Changing the password re-wraps it; nothing
 *    else is re-encrypted. A guest account's content key never leaves its device (no password to wrap it with, and no
 *    other device can sign in to a guest anyway) until "Create account" gives it one.
 *
 * Every content value is raw-DEFLATEd ([RawDeflate]: a ciphertext cannot be compressed, and base64 grows it by a third)
 * then AES-256-GCM with a fresh 96-bit nonce, written `e1:` + base64(nonce ‖ ciphertext ‖ tag), and
 * bound by its associated data to WHERE it is stored (row kind and id), so the server cannot move one row's content
 * into another's.
 */
@OptIn(ExperimentalEncodingApi::class)
object E2ee {
    const val PREFIX: String = "e1:"
    const val PBKDF2_ITERATIONS: Int = 600_000

    /** What the sync state says while an account with credentials has no key on this device ([SchedulerSyncEngine]). */
    const val SIGN_IN_FOR_ENCRYPTION: String =
        "Sign in again with your password to turn on end-to-end encryption; nothing syncs until then."

    private val provider get() = CryptographyProvider.Default

    /** The two secrets a password gives: what GoTrue is sent, and what wraps the content key. */
    class PasswordKeys(val loginSecret: String, internal val kek: ByteArray)

    /** The account's content key, in clear: what [encrypt]/[decrypt] use. */
    class ContentKey(val raw: ByteArray) {
        init { require(raw.size == 32) { "a content key is 32 bytes" } }
        fun encoded(): String = Base64.encode(raw)

        companion object {
            fun decoded(text: String): ContentKey = ContentKey(Base64.decode(text))
            fun random(): ContentKey = ContentKey(CryptographyRandom.nextBytes(32))
        }
    }

    /** Where an entity row's payload is stored: what its seal is bound to. */
    fun entityPlace(kind: String, id: String): String = "entity|$kind|$id"

    /** Where a History Unit row's delta is stored. */
    fun historyPlace(deviceId: String, category: String, deviceSeq: Long): String = "history|$deviceId|$category|$deviceSeq"

    /** The account's peer broadcast channel ([SchedulerPeerChannel]): transient, but it crosses the server too. */
    fun peerPlace(userId: String): String = "peer|$userId"

    /** Where a Claude Code log entry's texts are stored (`|title`, `|project`, `|body` appended). */
    fun claudeLogPlace(entryId: String): String = "claude|$entryId"

    /** The structure fields the server's clean-up reads (migration 20261121000000's `meta`): ids and an end time. */
    private val META_FIELDS = listOf("occurrences", "childListId", "parentListId", "end")

    /** The field row the server reads its database limit from, in clear (`forget_old_history`). */
    const val LIMIT_FIELD: String = "serverDatabaseLimitBytes"

    /**
     * **The one part of an entity row the server may read** (the user's choice, 2026-11-21): what `forget_old_history`
     * walks — a task's `occurrences` (cell ids) and `childListId`, a cell's `parentListId`, a panel's `end` — and the
     * value of the [LIMIT_FIELD] row. Ids and an instant; never a title, a note or a text. Null when the row has none.
     */
    fun entityMeta(kind: String, id: String, payload: String): JsonObject? {
        val element = runCatching { metaJson.parseToJsonElement(payload) }.getOrNull() ?: return null
        if (kind == EntityRows.FIELD_KIND) {
            return if (id == LIMIT_FIELD && element is JsonPrimitive) JsonObject(mapOf("value" to element)) else null
        }
        val obj = element as? JsonObject ?: return null
        val kept = META_FIELDS.mapNotNull { field -> obj[field]?.takeIf { it !is JsonNull }?.let { field to it } }.toMap()
        return if (kept.isEmpty()) null else JsonObject(kept)
    }

    private val metaJson = Json { ignoreUnknownKeys = true }

    /** Whether [text] is a value this file wrote — otherwise it is a row from before encryption (or the server's own). */
    fun isEncrypted(text: String?): Boolean = text != null && text.startsWith(PREFIX)

    suspend fun passwordKeys(email: String, password: String, iterations: Int = PBKDF2_ITERATIONS): PasswordKeys {
        val salt = ("omniapp-e2ee-v1|" + email.trim().lowercase()).encodeToByteArray()
        val master =
            runCatching {
                provider.get(PBKDF2).secretDerivation(SHA256, iterations, 256.bits, salt).deriveSecretToByteArray(password.encodeToByteArray())
            }.getOrElse { pbkdf2FromHmac(password.encodeToByteArray(), salt, iterations) }
        val login = hkdf(master, "omniapp auth v1")
        val kek = hkdf(master, "omniapp kek v1")
        return PasswordKeys(Base64.encode(login), kek)
    }

    /**
     * PBKDF2-HMAC-SHA256 for one 32-byte block (RFC 8018), over the provider's HMAC — for a platform whose provider has
     * no PBKDF2 (Android before API 26 has no `PBKDF2WithHmacSHA256`; the app's minSdk is 24). The same bytes as the
     * provider's (`E2eeTest` pins both).
     */
    internal suspend fun pbkdf2FromHmac(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = provider.get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, password).signatureGenerator()
        var u = mac.generateSignature(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = mac.generateSignature(u)
            for (k in out.indices) out[k] = (out[k].toInt() xor u[k].toInt()).toByte()
        }
        return out
    }

    private suspend fun hkdf(master: ByteArray, info: String): ByteArray =
        provider.get(HKDF)
            .secretDerivation(SHA256, 256.bits, ByteArray(0), info.encodeToByteArray())
            .deriveSecretToByteArray(master)

    suspend fun wrap(content: ContentKey, keys: PasswordKeys, userId: String): String =
        seal(keys.kek, content.raw, "account-key|$userId")

    /** Null when [wrapped] does not open with [keys] — the wrong password, or a key wrapped for another account. */
    suspend fun unwrap(wrapped: String, keys: PasswordKeys, userId: String): ContentKey? =
        runCatching { ContentKey(open(keys.kek, wrapped, "account-key|$userId")) }.getOrNull()

    suspend fun encrypt(key: ContentKey, plaintext: String, where: String): String =
        seal(key.raw, RawDeflate.compress(plaintext.encodeToByteArray()), where)

    /** The plaintext of [text] stored at [where]; throws [E2eeException] when it does not open with [key]. */
    suspend fun decrypt(key: ContentKey, text: String, where: String): String =
        runCatching { RawDeflate.decompress(open(key.raw, text, where)).decodeToString() }
            .getOrElse { throw E2eeException("a value stored at $where does not open with this account's key") }

    private suspend fun seal(key: ByteArray, plaintext: ByteArray, where: String): String {
        val cipher = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
        return PREFIX + Base64.encode(cipher.encrypt(plaintext, where.encodeToByteArray()))
    }

    private suspend fun open(key: ByteArray, text: String, where: String): ByteArray {
        require(text.startsWith(PREFIX)) { "not an encrypted value" }
        val cipher = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
        return cipher.decrypt(Base64.decode(text.removePrefix(PREFIX)), where.encodeToByteArray())
    }
}

/** A stored value that does not open with the account's key, or an attempt to send content with no key at all. */
class E2eeException(message: String) : Exception(message)
