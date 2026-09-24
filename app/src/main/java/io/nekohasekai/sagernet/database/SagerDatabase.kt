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
    version = 18,
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

    /**
     * Version 17 repairs the v16 identity-hash divergence introduced by 246cb1041,
     * which added `@ColumnInfo(defaultValue = "NULL")` to ssrBean/snellBean and
     * regenerated 16.json WITHOUT bumping the version. Devices holding a
     * pre-246cb1041 v16 database hard-crash at open, and an empty migration does
     * NOT pass Room's post-migration TableInfo validation (device DB carries
     * ssrBean/snellBean without a DEFAULT clause; 17.json expects "NULL" — Room
     * treats them as unequal).
     *
     * Fix: recreate proxy_entities with the verbatim createSql from 17.json.
     * CRITICAL: the copy MUST enumerate columns by name — the legacy v16 table
     * and the v17 schema share the same 40 columns in a DIFFERENT order
     * (verified on-device: 28/40 positions differ), so `INSERT ... SELECT *`
     * silently misaligns values by position (BLOB into TEXT →
     * "Unable to convert BLOB to string" at read). Room's TableInfo comparison
     * is order-insensitive, so the misaligned table passes validation and
     * corrupts the database.
     */
    object Migration16To17 : Migration(16, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `_new_proxy_entities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `type` INTEGER NOT NULL, `userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, `lifetimeRx` INTEGER NOT NULL DEFAULT 0, `lifetimeTx` INTEGER NOT NULL DEFAULT 0, `status` INTEGER NOT NULL, `ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, `speedTestMode` TEXT NOT NULL DEFAULT '', `speedTestDownloadBitsPerSecond` INTEGER NOT NULL DEFAULT 0, `speedTestUploadBitsPerSecond` INTEGER NOT NULL DEFAULT 0, `socksBean` BLOB, `httpBean` BLOB, `ssBean` BLOB, `ssrBean` BLOB DEFAULT NULL, `vmessBean` BLOB, `trojanBean` BLOB, `trojanGoBean` BLOB, `mieruBean` BLOB, `naiveBean` BLOB, `hysteriaBean` BLOB, `tuicBean` BLOB, `juicityBean` BLOB, `shadowQuicBean` BLOB, `trustTunnelBean` BLOB, `sshBean` BLOB, `wgBean` BLOB, `shadowTLSBean` BLOB, `anyTLSBean` BLOB, `chainBean` BLOB, `balancerBean` BLOB, `configBean` BLOB, `snellBean` BLOB DEFAULT NULL, `masterDnsVpnBean` BLOB, `awgBean` BLOB, `olcrtcBean` BLOB)",
            )
            database.execSQL(
                "INSERT INTO `_new_proxy_entities` (`id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `lifetimeRx`, `lifetimeTx`, `status`, `ping`, `uuid`, `error`, `speedTestMode`, `speedTestDownloadBitsPerSecond`, `speedTestUploadBitsPerSecond`, `socksBean`, `httpBean`, `ssBean`, `ssrBean`, `vmessBean`, `trojanBean`, `trojanGoBean`, `mieruBean`, `naiveBean`, `hysteriaBean`, `tuicBean`, `juicityBean`, `shadowQuicBean`, `trustTunnelBean`, `sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `balancerBean`, `configBean`, `snellBean`, `masterDnsVpnBean`, `awgBean`, `olcrtcBean`) " +
                    "SELECT `id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `lifetimeRx`, `lifetimeTx`, `status`, `ping`, `uuid`, `error`, `speedTestMode`, `speedTestDownloadBitsPerSecond`, `speedTestUploadBitsPerSecond`, `socksBean`, `httpBean`, `ssBean`, `ssrBean`, `vmessBean`, `trojanBean`, `trojanGoBean`, `mieruBean`, `naiveBean`, `hysteriaBean`, `tuicBean`, `juicityBean`, `shadowQuicBean`, `trustTunnelBean`, `sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `balancerBean`, `configBean`, `snellBean`, `masterDnsVpnBean`, `awgBean`, `olcrtcBean` FROM `proxy_entities`",
            )
            database.execSQL("DROP TABLE `proxy_entities`")
            database.execSQL("ALTER TABLE `_new_proxy_entities` RENAME TO `proxy_entities`")
            database.execSQL("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")
        }
    }

    /**
     * Version 18: additive `byedpiBean` column on proxy_entities for the embedded byeDPI
     * egress protocol. Matches the @ColumnInfo(defaultValue = "NULL") on the entity field
     * (DEFAULT NULL records dflt_value "NULL", same as the Migration14To15 repair columns).
     */
    object Migration17To18 : Migration(17, 18) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `byedpiBean` BLOB DEFAULT NULL")
        }
    }

    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            // 主庫損毀時 Room 只會在第一次查詢拋 SQLiteException，而這個 instance 是 lazy 單例，
            // 於是每次存取都再拋一次、形成啟動崩潰迴圈，使用者只能清資料、連節點一起清掉。
            // 建 builder 前先做魔數體檢，壞了就隔離讓 Room 重建。刻意不用
            // fallbackToDestructiveMigration：那會在任何遷移失敗時無聲清空全部節點，
            // 我方就是為了這個才把它拿掉的（見 Migration16To17 的教訓）。
            moe.matsuri.nb4a.utils.Util.quarantineIfNotSqlite(
                SagerNet.application.getDatabasePath(Key.DB_PROFILE),
            )
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(Migration13To14, Migration14To15, Migration16To17, Migration17To18)
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
