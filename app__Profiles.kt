package dev.sk2andy.materialbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** id is the stable storage key (never reused); name is only a label, so renaming keeps logins. */
data class Profile(val id: String, val name: String)

/** Profile list + active profile + last page per profile, kept in SharedPreferences. */
class ProfileList(context: Context) {

    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    val profiles = mutableListOf<Profile>()
    var activeId: String = ""
        private set

    init {
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles.add(Profile(o.getString("id"), o.getString("name")))
            }
        }
        if (profiles.isEmpty()) {
            profiles.add(newProfile("Main"))
            save()
        }
        activeId = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
    }

    fun active(): Profile = profiles.first { it.id == activeId }

    fun add(name: String): Profile {
        val p = newProfile(name)
        profiles.add(p)
        save()
        return p
    }

    fun rename(id: String, name: String) {
        val i = profiles.indexOfFirst { it.id == id }
        if (i >= 0) { profiles[i] = profiles[i].copy(name = name); save() }
    }

    fun remove(id: String) {
        profiles.removeAll { it.id == id }
        prefs.edit().remove(KEY_URL + id).apply()
        if (activeId == id) activeId = profiles.first().id
        save()
    }

    fun setActive(id: String) {
        activeId = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun lastUrl(id: String): String? = prefs.getString(KEY_URL + id, null)
    fun saveUrl(id: String, url: String) = prefs.edit().putString(KEY_URL + id, url).apply()

    private fun newProfile(name: String) =
        Profile("p" + UUID.randomUUID().toString().replace("-", "").take(12), name)

    private fun save() {
        val arr = JSONArray()
        profiles.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString(KEY_LIST, arr.toString()).putString(KEY_ACTIVE, activeId).apply()
    }

    companion object {
        const val MAX_PROFILES = 20
        private const val KEY_LIST = "list"
        private const val KEY_ACTIVE = "active"
        private const val KEY_URL = "url_"
    }
}
