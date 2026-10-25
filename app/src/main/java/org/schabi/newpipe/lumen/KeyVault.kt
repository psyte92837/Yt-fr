/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted with an AES-GCM key that lives in the Android Keystore.
 * Keys are kept in a separate preferences file that is excluded from backups and exports.
 */
object KeyVault {
    private const val ALIAS = "lumen_api_keys"
    private val prefs get() = Lumen.app.getSharedPreferences("lumen_vault", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(provider: String, apiKey: String) {
        if (apiKey.isBlank()) {
            prefs.edit().remove(provider).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = c.doFinal(apiKey.trim().toByteArray())
        val packed = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString(provider, packed).apply()
    }

    fun get(provider: String): String? {
        val packed = prefs.getString(provider, null) ?: return null
        return try {
            val (iv, data) = packed.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) {
            null
        }
    }

    fun has(provider: String) = prefs.contains(provider)
}
