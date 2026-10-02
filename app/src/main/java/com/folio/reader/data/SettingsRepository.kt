package com.folio.reader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "folio_settings")

enum class ReaderTheme(val label: String, val background: Long, val foreground: Long, val link: Long) {
    PAPER("Paper", 0xFFFFFCF7, 0xFF2A1F2D, 0xFF5B3E96),
    SEPIA("Sepia", 0xFFF4EAD5, 0xFF433422, 0xFF8A4B2A),
    NIGHT("Night", 0xFF17131C, 0xFFDAD2E2, 0xFFB9A2F0),
    BLACK("Black", 0xFF000000, 0xFFD4D4D6, 0xFFA9B8D0);

    val isDark get() = this == NIGHT || this == BLACK
}

enum class AppTheme(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("Black"),
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val fontSize: Int = 19,
    val serif: Boolean = true,
    val lineHeight: Float = 1.6f,
    val margin: Int = 24,
)

enum class AiModel(val id: String, val label: String, val description: String, val maxContextChars: Int) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "The most accurate. Recommended.", 2_800_000),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Faster and cheaper.", 2_800_000),
    HAIKU("claude-haiku-4-5", "Claude Haiku 4.5", "The cheapest. Smaller context (~500 pages).", 560_000);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("reader_theme")
        val fontSize = intPreferencesKey("reader_font_size")
        val serif = intPreferencesKey("reader_serif")
        val lineHeight = floatPreferencesKey("reader_line_height")
        val margin = intPreferencesKey("reader_margin")
        val model = stringPreferencesKey("ai_model")
        val apiKey = stringPreferencesKey("ai_api_key_enc")
        val geminiKey = stringPreferencesKey("ai_gemini_key_enc")
        val highlightColor = stringPreferencesKey("highlight_color")
        val appTheme = stringPreferencesKey("app_theme")
        val dailyGoal = intPreferencesKey("daily_goal_minutes")
        val accent = intPreferencesKey("accent_argb")
        val ttsRate = floatPreferencesKey("tts_rate")
        val volumeKeys = booleanPreferencesKey("volume_keys_turn_pages")
        val localAiUrl = stringPreferencesKey("local_ai_url")
        val localAiModel = stringPreferencesKey("local_ai_model")
        val localAiThink = booleanPreferencesKey("local_ai_think")
        val localAiDeep = booleanPreferencesKey("local_ai_deep")
    }

    /**
     * Send the local model ~2.5x more of the book (64K-token window). Measured with qwen3.5:9b on a real question:
     * twice as slow and no more accurate for "who is this?"; worth it mainly for recaps. Off by default.
     */
    val localAiDeep: Flow<Boolean> = context.dataStore.data.map { it[Keys.localAiDeep] ?: false }

    suspend fun setLocalAiDeep(on: Boolean) = context.dataStore.edit { it[Keys.localAiDeep] = on }

    /** Let the local model reason before answering: slower but more careful. */
    val localAiThink: Flow<Boolean> = context.dataStore.data.map { it[Keys.localAiThink] ?: false }

    suspend fun setLocalAiThink(on: Boolean) = context.dataStore.edit { it[Keys.localAiThink] = on }

    /** Address of an Ollama server on the reader's own computer (e.g. http://192.168.1.65:11434); null = off. */
    val localAiUrl: Flow<String?> = context.dataStore.data.map { it[Keys.localAiUrl]?.takeIf(String::isNotBlank) }

    val localAiModel: Flow<String> = context.dataStore.data.map { it[Keys.localAiModel]?.takeIf(String::isNotBlank) ?: DEFAULT_LOCAL_MODEL }

    suspend fun setLocalAi(url: String?, model: String?) = context.dataStore.edit { p ->
        if (url.isNullOrBlank()) p.remove(Keys.localAiUrl) else p[Keys.localAiUrl] = url.trim()
        if (model.isNullOrBlank()) p.remove(Keys.localAiModel) else p[Keys.localAiModel] = model.trim()
    }

    /** Volume down = next page, volume up = previous, while reading (not while the voice is reading aloud). */
    val volumeKeysTurnPages: Flow<Boolean> = context.dataStore.data.map { it[Keys.volumeKeys] ?: true }

    suspend fun setVolumeKeysTurnPages(on: Boolean) = context.dataStore.edit { it[Keys.volumeKeys] = on }

    /** Accent color (ARGB) for the main buttons; null = the default plum. */
    val accentColor: Flow<Int?> = context.dataStore.data.map { it[Keys.accent] }

    suspend fun setAccentColor(argb: Int?) = context.dataStore.edit { p ->
        if (argb == null) p.remove(Keys.accent) else p[Keys.accent] = argb
    }

    val dailyGoalMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.dailyGoal] ?: 20 }

    suspend fun setDailyGoal(minutes: Int) = context.dataStore.edit { it[Keys.dailyGoal] = minutes }

    val ttsRate: Flow<Float> = context.dataStore.data.map { it[Keys.ttsRate] ?: 1f }

    suspend fun setTtsRate(rate: Float) = context.dataStore.edit { it[Keys.ttsRate] = rate }

    val appTheme: Flow<AppTheme> = context.dataStore.data.map { p ->
        p[Keys.appTheme]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SYSTEM
    }

    /** Also moves the reader to its Night theme (or back to Paper) so the whole app matches. */
    suspend fun setAppTheme(theme: AppTheme) {
        context.dataStore.edit { it[Keys.appTheme] = theme.name }
        when (theme) {
            AppTheme.DARK -> updateReader { it.copy(theme = ReaderTheme.NIGHT) }
            AppTheme.BLACK -> updateReader { it.copy(theme = ReaderTheme.BLACK) }
            AppTheme.LIGHT -> updateReader { if (it.theme.isDark) it.copy(theme = ReaderTheme.PAPER) else it }
            AppTheme.SYSTEM -> Unit
        }
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            theme = p[Keys.theme]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.PAPER,
            fontSize = p[Keys.fontSize] ?: 19,
            serif = (p[Keys.serif] ?: 1) == 1,
            lineHeight = p[Keys.lineHeight] ?: 1.6f,
            margin = p[Keys.margin] ?: 24,
        )
    }

    suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(readerSettings.first())
        context.dataStore.edit { p ->
            p[Keys.theme] = next.theme.name
            p[Keys.fontSize] = next.fontSize
            p[Keys.serif] = if (next.serif) 1 else 0
            p[Keys.lineHeight] = next.lineHeight
            p[Keys.margin] = next.margin
        }
    }

    val model: Flow<AiModel> = context.dataStore.data.map { AiModel.fromId(it[Keys.model]) }

    suspend fun setModel(model: AiModel) = context.dataStore.edit { it[Keys.model] = model.id }

    val highlightColor: Flow<String> = context.dataStore.data.map { it[Keys.highlightColor] ?: "yellow" }

    suspend fun setHighlightColor(color: String) = context.dataStore.edit { it[Keys.highlightColor] = color }

    val apiKey: Flow<String?> = context.dataStore.data.map { p -> p[Keys.apiKey]?.let(KeyCipher::decrypt) }

    val hasApiKey: Flow<Boolean> = apiKey.map { !it.isNullOrBlank() }

    suspend fun setApiKey(key: String?) = context.dataStore.edit { p ->
        if (key.isNullOrBlank()) p.remove(Keys.apiKey) else p[Keys.apiKey] = KeyCipher.encrypt(key.trim())
    }

    /** Free Google AI Studio key for the Gemini free tier. */
    val geminiKey: Flow<String?> = context.dataStore.data.map { p -> p[Keys.geminiKey]?.let(KeyCipher::decrypt) }

    suspend fun setGeminiKey(key: String?) = context.dataStore.edit { p ->
        if (key.isNullOrBlank()) p.remove(Keys.geminiKey) else p[Keys.geminiKey] = KeyCipher.encrypt(key.trim())
    }

    companion object {
        /** Fits a 12 GB GPU entirely and answers well in English and Spanish. */
        const val DEFAULT_LOCAL_MODEL = "qwen3.5:9b"
    }
}

/** Encrypts the API key with an AES-GCM key that never leaves the Android Keystore. */
private object KeyCipher {
    private const val ALIAS = "folio_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    fun decrypt(stored: String): String? = runCatching {
        val (iv, ct) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
        String(cipher.doFinal(unb64(ct)), Charsets.UTF_8)
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
