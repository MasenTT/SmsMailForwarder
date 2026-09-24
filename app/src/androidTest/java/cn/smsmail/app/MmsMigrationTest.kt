package cn.smsmail.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmsMigrationTest {
    @Test fun version3UpgradePreservesSmsQueueAndCreatesEncryptedAttachmentTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MailApp>()
        val name = "mms-migration-test.db"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("cn.smsmail.app.MailDatabase/3.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>("legacy", "legacy-fingerprint", "encrypted-source", "encrypted-body", 10L, 1L, "SIM1", "默认收件人"))
            old.execSQL("INSERT INTO deliveries VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("legacy-job", "legacy", "default@example.com", "PENDING", 2, "等待重试", 10L))
            old.version = 3
        }
        val database = Room.databaseBuilder(context, MailDatabase::class.java, name)
            .addMigrations(mmsMigration(), mailCustomizationMigration()).build()
        try {
            val event = database.dao().event("legacy")!!
            assertEquals(MessageKind.SMS, event.kind)
            assertEquals("", event.subject)
            assertEquals("PENDING", database.dao().delivery("legacy-job")!!.state)
            assertTrue(database.dao().attachments("legacy").isEmpty())
            assertEquals(5, database.openHelper.readableDatabase.version)
            assertEquals(EmailMetadata.values().fold(0) { mask, item -> mask or item.bit }, event.emailMetadataMask)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
