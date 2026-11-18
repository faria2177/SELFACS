package com.tom.rv2ide.github

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores GitHub PAT only in Android Keystore-backed encrypted preferences. */
class GitHubTokenStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(): String? = preferences.getString(KEY_TOKEN, null)
    fun save(token: String) {
        require(GitHubValidation.isValidToken(token)) { "Invalid token format" }
        check(preferences.edit().putString(KEY_TOKEN, token).commit()) { "Secure token storage failed" }
    }
    fun clear() {
        check(preferences.edit().remove(KEY_TOKEN).commit()) { "Could not clear stored token" }
    }

    companion object {
        private const val PREFS_NAME = "selfacs_github_secure"
        private const val KEY_TOKEN = "github_pat"
    }
}
