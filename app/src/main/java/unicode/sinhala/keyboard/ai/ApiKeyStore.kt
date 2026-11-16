package unicode.sinhala.keyboard.ai

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "ai_encrypted_keys",
        Context.MODE_PRIVATE
    )

    private val alias = "fox_ai_secret_key_v1"

    @RequiresApi(Build.VERSION_CODES.M)
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    fun save(provider: String, apiKey: String) {
        require(Build.VERSION.SDK_INT >= 23) {
            "Android 6.0 or newer is required"
        }
        require(apiKey.isNotBlank()) {
            "API Key cannot be empty"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val encrypted = cipher.doFinal(
            apiKey.trim().toByteArray(Charsets.UTF_8)
        )

        val iv = Base64.encodeToString(
            cipher.iv, Base64.NO_WRAP
        )
        val data = Base64.encodeToString(
            encrypted, Base64.NO_WRAP
        )

        prefs.edit()
            .putString("api_$provider", "$iv:$data")
            .apply()
    }

    fun load(provider: String): String? {
        if (Build.VERSION.SDK_INT < 23) return null

        val stored = prefs.getString(
            "api_$provider", null
        ) ?: return null

        return runCatching {
            val parts = stored.split(':', limit = 2)
            require(parts.size == 2)

            val iv = Base64.decode(
                parts[0], Base64.NO_WRAP
            )
            val data = Base64.decode(
                parts[1], Base64.NO_WRAP
            )

            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            )
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(data),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    fun remove(provider: String) {
        prefs.edit()
            .remove("api_$provider")
            .apply()
    }
}
