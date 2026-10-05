/*
 * BioPay - biometric payment assistance for supported payment apps.
 *
 * Copyright (C) 2026 kiriashi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.kiriashi.biopay.storage

import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.withFileLock
import android.content.Context
import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the password with Keystore; biometric authorization is enforced by the payment flow. */
object PasswordCipher {

    const val PASSWORD_LENGTH = 6
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val KEY_VERSION = "_compat_v3"
    private const val BOUND_PREFIX = "bp-app-v1:"
    private const val LEGACY_WECHAT_PACKAGE = "com.tencent.mm"

    private val keyAlias get() = PrefKeys.keystoreAlias + KEY_VERSION
    private val keyStoreLock = Any()
    @Volatile private var keyStore: KeyStore? = null

    data class DecryptOperation(
        val cipher: Cipher,
        val ciphertext: ByteArray
    )

    private fun getKeyStore(): KeyStore {
        keyStore?.let { return it }
        synchronized(keyStoreLock) {
            keyStore?.let { return it }
            return KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.also { keyStore = it }
        }
    }

    private fun getSecretKey(createIfMissing: Boolean = false): SecretKey {
        synchronized(keyStoreLock) {
            val store = getKeyStore()
            val entry = store.getKey(keyAlias, null)
            if (entry == null) {
                if (createIfMissing) {
                    if (store.containsAlias(keyAlias)) throw UnavailableKeyException()
                    return generateKey()
                }
                throw UnrecoverableKeyException("payment encryption key is missing")
            }
            val key = entry as? SecretKey ?: throw UnavailableKeyException()
            if (key.algorithm != KeyProperties.KEY_ALGORITHM_AES) throw UnavailableKeyException()
            return key
        }
    }

    private class UnavailableKeyException : UnrecoverableKeyException("payment encryption key is unusable")

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun isAppBoundCiphertext(encoded: String): Boolean = encoded.startsWith(BOUND_PREFIX)

    private fun associatedData(packageName: String): ByteArray =
        "BioPay:payment-password:v1:$packageName".toByteArray(Charsets.UTF_8)

    /** Recovery is allowed only after the user authenticates to save a newly entered password. */
    fun createEncryptionCipher(context: Context, packageName: String): Cipher {
        require(context.packageName == packageName) { "payment password owner mismatch" }
        return synchronized(keyStoreLock) {
            withFileLock(File(context.filesDir, "biopay_password_key.lock")) {
                try {
                    encryptionCipher(packageName)
                } catch (error: Exception) {
                    if (!isUnusableKey(error)) throw error
                    ModuleLog.w(error) { "payment encryption key unusable; recreating for password save" }
                    try {
                        keyStore = null
                        getKeyStore().deleteEntry(keyAlias)
                        encryptionCipher(packageName)
                    } catch (retry: Exception) {
                        if (retry !== error) retry.addSuppressed(error)
                        ModuleLog.d(retry) { "payment encryption key recovery failed" }
                        throw retry
                    }
                }
            }
        }
    }

    private fun encryptionCipher(packageName: String): Cipher {
        var phase = "provider"
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            phase = "key"
            val key = getSecretKey(createIfMissing = true)
            phase = "cipher initialization"
            cipher.init(Cipher.ENCRYPT_MODE, key)
            phase = "app binding"
            cipher.updateAAD(associatedData(packageName))
            return cipher
        } catch (error: Exception) {
            ModuleLog.d(error) { "password encryption initialization failed: phase=$phase" }
            throw error
        }
    }

    private fun isUnusableKey(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(16) {
            val cause = current ?: return false
            if (cause is KeyPermanentlyInvalidatedException || cause is UnavailableKeyException) return true
            if (Build.VERSION.SDK_INT >= 33 && cause is KeyStoreException &&
                cause.numericErrorCode == KeyStoreException.ERROR_KEY_CORRUPTED) return true
            if (cause.javaClass.name == "android.security.KeyStoreException") {
                // Public numeric codes combine KeyMint failures. Android's -33
                // carries this specific message on both old and current providers.
                if (cause.message?.startsWith("Invalid key blob", ignoreCase = true) == true) return true
            }
            current = cause.cause
        }
        return false
    }

    fun encrypt(plainText: CharArray, cipher: Cipher): String {
        require(plainText.size == PASSWORD_LENGTH && plainText.all { it in '0'..'9' }) { "Invalid password length" }
        val bytes = ByteArray(plainText.size) { plainText[it].code.toByte() }
        return try {
            val encrypted = cipher.doFinal(bytes)
            BOUND_PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    /** Loads the Keystore key so the tap that opens the sheet does not pay the binder round trip. */
    fun warmUp() {
        getSecretKey()
    }

    fun createDecryptOperation(encoded: String, packageName: String): DecryptOperation? {
        return try {
            val bound = isAppBoundCiphertext(encoded)
            // Unbound records predate multi-app support and belong only to WeChat.
            if (!bound && packageName != LEGACY_WECHAT_PACKAGE) return null
            val payload = if (bound) encoded.removePrefix(BOUND_PREFIX) else encoded
            val combined = Base64.decode(payload, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getSecretKey(),
                    GCMParameterSpec(GCM_TAG_LENGTH, iv)
                )
                if (bound) updateAAD(associatedData(packageName))
            }
            DecryptOperation(cipher, ciphertext)
        } catch (e: Throwable) {
            ModuleLog.w(e) { "failed to prepare password decryption" }
            null
        }
    }

    fun decryptToCharArray(operation: DecryptOperation): CharArray? {
        var bytes: ByteArray? = null
        return try {
            bytes = operation.cipher.doFinal(operation.ciphertext)
            if (bytes.size != PASSWORD_LENGTH || bytes.any { it.toInt() !in 48..57 }) return null
            CharArray(bytes.size) { i -> (bytes!![i].toInt() and 0xff).toChar() }
        } catch (e: Throwable) {
            ModuleLog.w(e) { "password decryption failed" }
            null
        } finally {
            bytes?.fill(0)
            operation.ciphertext.fill(0)
        }
    }

}
