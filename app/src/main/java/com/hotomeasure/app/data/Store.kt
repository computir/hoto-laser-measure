package com.hotomeasure.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("hoto_measure")

data class PairedMeter(val address: String, val name: String, val tokenHex: String)

enum class DisplayUnit(val label: String) {
    M("m"), CM("cm"), MM("mm"), FT_IN("ft-in"), FT("ft"), IN("in");
}

data class Settings(
    val unit: DisplayUnit = DisplayUnit.FT_IN,
    val decimals: Int = 2,
    val inchFraction: Int = 16, // round ft-in / in to 1/16"
)

@Serializable
data class Measurement(
    val id: Long,
    val time: Long,
    val kind: String, // "distance", "area", "volume"
    val mm: List<Int>, // the side lengths that went into it
    val label: String = "",
)

class Store(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val paired: Flow<PairedMeter?> = context.dataStore.data.map { p ->
        val a = p[ADDRESS]; val t = p[TOKEN]
        if (a == null || t == null) null else PairedMeter(a, p[NAME] ?: "HOTO Laser Measure", t)
    }.distinctUntilChanged() // saving history must not look like a new pairing

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            unit = p[UNIT]?.let { runCatching { DisplayUnit.valueOf(it) }.getOrNull() } ?: DisplayUnit.FT_IN,
            decimals = p[DECIMALS] ?: 2,
            inchFraction = p[FRACTION] ?: 16,
        )
    }.distinctUntilChanged()

    val history: Flow<List<Measurement>> = context.dataStore.data.map { decode(it) }.distinctUntilChanged()

    suspend fun savePaired(m: PairedMeter) = context.dataStore.edit {
        it[ADDRESS] = m.address; it[NAME] = m.name; it[TOKEN] = m.tokenHex
    }

    suspend fun forget() = context.dataStore.edit {
        it.remove(ADDRESS); it.remove(NAME); it.remove(TOKEN)
    }

    suspend fun updateSettings(f: (Settings) -> Settings) = context.dataStore.edit { p ->
        val cur = Settings(
            unit = p[UNIT]?.let { runCatching { DisplayUnit.valueOf(it) }.getOrNull() } ?: DisplayUnit.FT_IN,
            decimals = p[DECIMALS] ?: 2,
            inchFraction = p[FRACTION] ?: 16,
        )
        val n = f(cur)
        p[UNIT] = n.unit.name; p[DECIMALS] = n.decimals; p[FRACTION] = n.inchFraction
    }

    suspend fun editHistory(f: (List<Measurement>) -> List<Measurement>) = context.dataStore.edit { p ->
        p[HISTORY] = json.encodeToString(f(decode(p)).take(MAX_HISTORY))
    }

    private fun decode(p: Preferences): List<Measurement> =
        p[HISTORY]?.let { runCatching { json.decodeFromString<List<Measurement>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_HISTORY = 1000
        val ADDRESS = stringPreferencesKey("address")
        val NAME = stringPreferencesKey("name")
        val TOKEN = stringPreferencesKey("token")
        val UNIT = stringPreferencesKey("unit")
        val DECIMALS = intPreferencesKey("decimals")
        val FRACTION = intPreferencesKey("fraction")
        val HISTORY = stringPreferencesKey("history")
    }
}
