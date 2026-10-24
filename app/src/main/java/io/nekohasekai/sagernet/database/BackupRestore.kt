package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase

object BackupRestore {
    // One connection commits both files with SQLite's rollback/super-journal.
    // https://www.sqlite.org/lang_attach.html (both Room databases use TRUNCATE).
    @Synchronized
    fun commit(settings: List<KeyValuePair>?, settingsKeys: Set<String>? = null, restoreProfilesAndRules: () -> Unit) {
        val room = SagerDatabase.instance
        if (settings == null) {
            room.runInTransaction { restoreProfilesAndRules() }
            return
        }
        // Initialize the public schema before attaching its file.
        PublicDatabase.instance.openHelper.writableDatabase
        val db = room.openHelper.writableDatabase
        val path = SagerNet.application.getDatabasePath(Key.DB_PUBLIC).absolutePath
        db.execSQL("ATTACH DATABASE ? AS restore_settings", arrayOf(path))
        try {
            for (schema in listOf("main", "restore_settings")) {
                val mode = db.query("PRAGMA $schema.journal_mode").use {
                    check(it.moveToFirst()) { "Cannot read database journal mode" }
                    it.getString(0).lowercase()
                }
                check(mode in setOf("delete", "truncate", "persist")) { "Backup restore requires rollback journals" }
                db.execSQL("PRAGMA $schema.synchronous=FULL")
            }
            room.runInTransaction {
                restoreProfilesAndRules()
                if (settingsKeys == null) db.execSQL("DELETE FROM restore_settings.KeyValuePair")
                else {
                    require(settingsKeys.isNotEmpty())
                    db.execSQL("DELETE FROM restore_settings.KeyValuePair WHERE `key` IN (${settingsKeys.joinToString { "?" }})", settingsKeys.toTypedArray())
                }
                db.compileStatement("INSERT INTO restore_settings.KeyValuePair (`key`, valueType, value) VALUES (?, ?, ?)").use { statement ->
                    settings.forEach { pair ->
                        statement.clearBindings()
                        statement.bindString(1, pair.key)
                        statement.bindLong(2, pair.valueType.toLong())
                        statement.bindBlob(3, pair.value)
                        statement.executeInsert()
                    }
                }
            }
        } finally {
            db.execSQL("DETACH DATABASE restore_settings")
        }
    }
}
