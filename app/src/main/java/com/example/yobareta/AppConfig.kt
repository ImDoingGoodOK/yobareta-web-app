package com.example.yobareta

import android.content.Context
import java.util.Properties

object AppConfig {
    private const val PREFS_NAME = "yobareta_app_config"
    private const val KEY_BASE_URL = "custom_base_url"

    // Default to the host PC's LAN IP so both the Android Emulator and a physical phone
    // scanning the QR code on the same Wi-Fi network can reach the Patient PWA server.
    private const val DEFAULT_LAN_URL = "http://10.48.159.28:8080"

    @Volatile
    var DEFAULT_CLOUD_RUN_BASE_URL: String = DEFAULT_LAN_URL
        private set

    @Volatile
    var API_SECRET: String = ""
        private set

    /**
     * Loads optional git-ignored `app/src/main/assets/yobareta.env` and user SharedPreferences override.
     */
    fun initFromContext(context: Context) {
        try {
            val assets = context.assets.list("")
            if (assets != null && "yobareta.env" in assets) {
                context.assets.open("yobareta.env").use { input ->
                    val props = Properties().apply { load(input) }
                    val url = props.getProperty("CLOUD_RUN_BASE_URL")?.trim().orEmpty()
                    if (url.isNotEmpty() && !url.contains("your-cloud-run-service")) {
                        DEFAULT_CLOUD_RUN_BASE_URL = url.trimEnd('/')
                    }
                    val secret = props.getProperty("YOBARETA_API_SECRET")?.trim().orEmpty()
                    if (secret.isNotEmpty() && !secret.startsWith("replace-with")) {
                        API_SECRET = secret
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Check if user customized the Server URL in the tablet UI
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
            if (savedUrl.isNotEmpty()) {
                DEFAULT_CLOUD_RUN_BASE_URL = savedUrl.trimEnd('/')
            }
        } catch (_: Exception) {
        }
    }

    fun updateServerBaseUrl(context: Context, newUrl: String) {
        val cleaned = newUrl.trim().trimEnd('/')
        if (cleaned.isNotEmpty()) {
            DEFAULT_CLOUD_RUN_BASE_URL = cleaned
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BASE_URL, cleaned)
                .apply()
        }
    }
}
