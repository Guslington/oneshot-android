package com.oneshotchat.android.data

import android.content.Context
import com.oneshotchat.android.crypto.Identity
import com.oneshotchat.android.net.OneShotApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local, device-side persistence:
 *  - the identity (the private seed) in an owner-only file inside internal storage,
 *  - app settings (server, display name, client URL) and
 *  - remembered room fingerprints (to warn when a name later points elsewhere) and
 *    saved contacts with petnames (recognise people by full fingerprint, not name).
 *
 * The identity file is a secret. It lives in the app's private storage and is never
 * logged or transmitted. Users can still export it via [Identity.toFile].
 */
class Storage(context: Context) {
    private val dir = context.filesDir
    private val identityFile = File(dir, "self.identity").apply { restrictPermissions() }
    private val prefs = context.getSharedPreferences("oneshotchat", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Identity --------------------------------------------------------------

    fun hasIdentity(): Boolean = identityFile.exists()

    fun loadIdentity(): Identity? =
        if (identityFile.exists()) runCatching { Identity.fromFile(identityFile.readText()) }.getOrNull()
        else null

    fun saveIdentity(identity: Identity) {
        identityFile.writeText(identity.toFile())
        identityFile.restrictPermissions()
    }

    fun clearIdentity() {
        identityFile.delete()
    }

    // ---- Settings --------------------------------------------------------------

    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER, OneShotApi.DEFAULT_BASE_URL) ?: OneShotApi.DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var clientUrl: String?
        get() = prefs.getString(KEY_CLIENT_URL, DEFAULT_CLIENT_URL)
        set(value) = prefs.edit().putString(KEY_CLIENT_URL, value).apply()

    var incognito: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO, false)
        set(value) = prefs.edit().putBoolean(KEY_INCOGNITO, value).apply()

    var backgroundNotifications: Boolean
        get() = prefs.getBoolean(KEY_BG_NOTIFS, false)
        set(value) = prefs.edit().putBoolean(KEY_BG_NOTIFS, value).apply()

    /** Cursor of the last mention the user has seen (see the mentions feed). */
    var mentionsSeenCursor: String?
        get() = prefs.getString(KEY_MENTIONS_SEEN, null)
        set(value) = prefs.edit().putString(KEY_MENTIONS_SEEN, value).apply()

    // ---- Remembered rooms ------------------------------------------------------

    fun rememberRoom(name: String, fingerprint: String) {
        prefs.edit().putString(roomKey(name), fingerprint).apply()
    }

    fun rememberedRoom(name: String): String? = prefs.getString(roomKey(name), null)

    // ---- Contacts / petnames ---------------------------------------------------

    @Serializable
    data class Contact(val fingerprint: String, val petname: String)

    fun contacts(): List<Contact> =
        runCatching { json.decodeFromString<List<Contact>>(prefs.getString(KEY_CONTACTS, "[]")!!) }
            .getOrDefault(emptyList())

    fun saveContact(fingerprint: String, petname: String) {
        val updated = contacts().filterNot { it.fingerprint == fingerprint } + Contact(fingerprint, petname)
        prefs.edit().putString(KEY_CONTACTS, json.encodeToString(updated)).apply()
    }

    fun removeContact(fingerprint: String) {
        val updated = contacts().filterNot { it.fingerprint == fingerprint }
        prefs.edit().putString(KEY_CONTACTS, json.encodeToString(updated)).apply()
    }

    private fun File.restrictPermissions() {
        runCatching {
            setReadable(false, false); setReadable(true, true)
            setWritable(false, false); setWritable(true, true)
        }
    }

    companion object {
        const val DEFAULT_CLIENT_URL = "https://github.com/oneshotchat/android-client"
        private const val KEY_SERVER = "server_base_url"
        private const val KEY_NAME = "display_name"
        private const val KEY_CLIENT_URL = "client_url"
        private const val KEY_INCOGNITO = "incognito"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_BG_NOTIFS = "bg_notifications"
        private const val KEY_MENTIONS_SEEN = "mentions_seen_cursor"
        private fun roomKey(name: String) = "room_fp_$name"
    }
}
