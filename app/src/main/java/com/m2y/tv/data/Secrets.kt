package com.m2y.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals playlist credentials with an AES-256-GCM key that never leaves Android Keystore.
 * Keystore calls take tens of ms: call off the main thread.
 */
object Secrets {
    private const val ALIAS = "m2y_tv_creds"
    private const val IV = 12

    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: run {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }.also { cached = it }
    }

    fun seal(creds: Creds): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(creds.encode().toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): Creds {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV))
        return Creds.decode(String(c.doFinal(b, IV, b.size - IV)))
    }
}
