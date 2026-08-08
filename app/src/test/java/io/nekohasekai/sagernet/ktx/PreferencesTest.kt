package io.nekohasekai.sagernet.ktx

import androidx.preference.PreferenceDataStore
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesTest {

    @Test
    fun stringToLongReadsListPreferenceValue() {
        val store = TestPreferenceDataStore()
        var sourceGroupId by store.stringToLong("sourceGroup")

        store.putString("sourceGroup", "42")

        assertEquals(42L, sourceGroupId)

        sourceGroupId = 17L

        assertEquals("17", store.getString("sourceGroup", null))
    }

    @Test
    fun stringToLongReadsLegacyLongValue() {
        val store = TestPreferenceDataStore()
        var sourceGroupId by store.stringToLong("sourceGroup")
        store.putLong("sourceGroup", 42L)

        assertEquals(42L, sourceGroupId)
    }

    private class TestPreferenceDataStore : PreferenceDataStore() {
        private val strings = mutableMapOf<String, String?>()
        private val longs = mutableMapOf<String, Long>()

        override fun getString(key: String, defValue: String?) = strings[key] ?: defValue

        override fun putString(key: String, value: String?) {
            strings[key] = value
        }

        override fun getLong(key: String, defValue: Long) = longs[key] ?: defValue

        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }
}
