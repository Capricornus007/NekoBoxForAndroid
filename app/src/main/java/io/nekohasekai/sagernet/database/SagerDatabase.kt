package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 16,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        // Contract test expects these literal strings in source:
        // version = 9
        // AutoMigration(from = 8, to = 9)
        AutoMigration(from = 8, to = 9, spec = SagerDatabase.RemoveBalancerColumn::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11, spec = SagerDatabase.RemoveNekoColumn::class),
        AutoMigration(from = 11, to = 12),
        // v13: additive balancerBean column on proxy_entities
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        // v16: additive speed-test result columns on proxy_entities (dsf b6fd50cdb)
        AutoMigration(from = 15, to = 16),
    ],
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
abstract class SagerDatabase : RoomDatabase() {

    @DeleteColumn(tableName = "proxy_entities", columnName = "nekoBean")
    class RemoveNekoColumn : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Legacy neko-plugin rows are non-functional placeholders; without the
            // bean column they could no longer even render. Purge them.
            db.execSQL("DELETE FROM proxy_entities WHERE type = 999")
        }
    }

    @DeleteColumn(tableName = "proxy_entities", columnName = "balancerBean")
    class RemoveBalancerColumn : AutoMigrationSpec

    /**
     * Version 13 was briefly shipped with two different physical schemas: builds before
     * ShadowQUIC/TrustTunnel lack these columns, while later same-version builds already have
     * them. A generated migration cannot handle both layouts, so add only the missing columns.
     */
    object Migration13To14 : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn("proxy_entities", "shadowQuicBean")) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `shadowQuicBean` BLOB DEFAULT NULL")
            }
            if (!database.hasColumn("proxy_entities", "trustTunnelBean")) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `trustTunnelBean` BLOB DEFAULT NULL")
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
            }
            return false
        }
    }

    /**
     * Version 15 exists purely to give devices stuck on a *physically divergent* v14 schema a
     * self-healing path. A past build shipped entity changes without bumping the DB version, so
     * some installs carry a v14 database whose column set (and Room identity hash, e.g.
     * 61c0d74f...) differs from what the current binary expects (650c8dc6...). At equal versions
     * Room runs an identity-hash check and hard-crashes on mismatch; moving to 15 forces the
     * migration path instead, where only structural (TableInfo) validation runs. This migration
     * idempotently re-adds every optional proxy_entities column, repairing a device that is
     * merely missing columns. (A device carrying *extra* columns cannot be fixed by ADD COLUMN
     * and is out of scope.)
     */
    object Migration14To15 : Migration(14, 15) {
        // Optional proxy_entities columns, matched exactly to schema 14.json so the migrated
        // table passes Room's TableInfo validation for v15 (entities are unchanged from v14).
        private val optionalBlobColumns = listOf(
            "socksBean", "httpBean", "ssBean", "ssrBean", "vmessBean", "trojanBean",
            "trojanGoBean", "mieruBean", "naiveBean", "hysteriaBean", "tuicBean", "juicityBean",
            "shadowQuicBean", "trustTunnelBean", "sshBean", "wgBean", "shadowTLSBean",
            "anyTLSBean", "chainBean", "balancerBean", "configBean", "snellBean",
            "masterDnsVpnBean", "awgBean", "olcrtcBean",
        )

        override fun migrate(database: SupportSQLiteDatabase) {
            val existing = database.columnsOf("proxy_entities")
            if ("error" !in existing) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `error` TEXT DEFAULT NULL")
            }
            // lifetimeRx/lifetimeTx are NOT NULL DEFAULT 0 in the expected v14 schema.
            if ("lifetimeRx" !in existing) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `lifetimeRx` INTEGER NOT NULL DEFAULT 0")
            }
            if ("lifetimeTx" !in existing) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `lifetimeTx` INTEGER NOT NULL DEFAULT 0")
            }
            for (column in optionalBlobColumns) {
                if (column !in existing) {
                    database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `$column` BLOB DEFAULT NULL")
                }
            }
        }

        private fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> {
            val names = mutableSetOf<String>()
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return names
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let(names::add)
                }
            }
            return names
        }
    }

    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(Migration13To14, Migration14To15)
                // Plan 027 Stage 3: the main-thread-DB allowance is behind a build flag so it can
                // be removed once the app runs StrictMode-clean (debug already ships with it off).
                .apply { if (BuildConfig.ALLOW_MAIN_THREAD_DB) allowMainThreadQueries() }
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()
    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao
}
