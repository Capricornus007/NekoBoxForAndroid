package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RuleDnsMigrationTest {
    @Test
    fun migrate15To16_preservesRuleAndLeavesDnsOptedOut() {
        val context = RuntimeEnvironment.getApplication()
        val name = "rule-dns-migration-test"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/io.nekohasekai.sagernet.database.SagerDatabase/15.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index)
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                            for (i in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO rules (id, name, config, userOrder, enabled, domains, ip, port, sourcePort, network, source, protocol, ruleset, outbound, packages) " +
                "VALUES (7, 'bank', '', 3, 1, '', '', '', '', '', '', '', '', 42, 'test.bank')",
        )
        helper.close()
        val database = Room.databaseBuilder(context, SagerDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val rule = database.rulesDao().allRules().single()
            assertEquals(7L, rule.id)
            assertEquals("bank", rule.name)
            assertEquals(42L, rule.outbound)
            assertEquals(3L, rule.userOrder)
            assertEquals(setOf("test.bank"), rule.packages)
            assertFalse(rule.dnsThroughOutbound)
            assertEquals("", rule.dnsServer)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
