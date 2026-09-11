package art.elisa

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The invite code is the only secret on the device; keep it encrypted at rest. */
class Store(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "elisart",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var code: String?
        get() = prefs.getString("code", null)
        set(v) = prefs.edit().putString("code", v).apply()

    /** BCP-47 tag for voice input; Persian unless changed in Settings. */
    var speechLanguage: String
        get() = prefs.getString("speech", "fa-IR") ?: "fa-IR"
        set(v) = prefs.edit().putString("speech", v).apply()

    var serverUrl: String
        get() = prefs.getString("server", null) ?: BuildConfig.SERVER_URL
        set(v) = prefs.edit().putString("server", v.trimEnd('/')).apply()
}
