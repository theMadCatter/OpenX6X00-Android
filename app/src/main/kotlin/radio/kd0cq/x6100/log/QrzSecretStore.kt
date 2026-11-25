// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// QRZ username and password. EncryptedSharedPreferences keeps them in the Android Keystore.
// They are not in the repo, not in log lines, and not in the QSO table. A failed keystore
// open returns blanks rather than crashing Setup: the lookup then says the credentials
// are missing.

package radio.kd0cq.x6100.log

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class QrzSecretStore(context: Context) {
    private val prefs: SharedPreferences? = open(context.applicationContext)

    fun username(): String = prefs?.getString(USER, "").orEmpty()

    /** The password. Do not log this, and do not put it in a crash report. */
    fun password(): String = prefs?.getString(PASS, "").orEmpty()

    fun hasCredentials(): Boolean = username().isNotBlank() && password().isNotBlank()

    fun write(username: String, password: String) {
        prefs?.edit()?.putString(USER, username.trim())?.putString(PASS, password)?.apply()
    }

    fun clear() {
        prefs?.edit()?.remove(USER)?.remove(PASS)?.apply()
    }

    private companion object {
        const val USER = "qrz_user"
        const val PASS = "qrz_pass"
        const val FILE = "qrz_secrets"

        fun open(context: Context): SharedPreferences? = runCatching {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }
}
