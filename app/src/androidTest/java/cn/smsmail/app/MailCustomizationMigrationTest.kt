package cn.smsmail.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailCustomizationMigrationTest {
    @Test fun version4UpgradePreservesDataAndInitializesPriorityAndMailSnapshots() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MailApp>()
        val name = "mail-customization-migration.db"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("cn.smsmail.app.MailDatabase/4.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database")
            }
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO contacts VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("contact", "联系人", "legacy@example.com", "legacy@example.com", "", 1L, 1L))
            old.execSQL("INSERT INTO rules VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>("later", "后建规则", ".*", "", 1, 20L, "CUSTOM", 1))
            old.execSQL("INSERT INTO rules VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>("earlier", "先建规则", "告警", "", 1, 10L, "CUSTOM", 1))
            old.execSQL("INSERT INTO rule_contacts VALUES(?,?)", arrayOf("earlier", "contact"))
            old.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("legacy", "fingerprint", "encrypted-source", "encrypted-body", 30L, 20L, "SIM 2", "先建规则", "MMS", "encrypted-subject", 1))
            old.execSQL("INSERT INTO deliveries VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("legacy-job", "legacy", "legacy@example.com", "PENDING", 2, "等待重试", 30L))
            old.execSQL("INSERT INTO event_attachments VALUES(?,?,?,?,?)", arrayOf<Any>("attachment", "legacy", "encrypted-name", "image/jpeg", byteArrayOf(1, 2, 3)))
            old.version = 4
        }

        val database = Room.databaseBuilder(context, MailDatabase::class.java, name)
            .addMigrations(mailCustomizationMigration()).build()
        try {
            assertEquals(5, database.openHelper.readableDatabase.version)
            val rules = database.dao().rules()
            assertEquals(listOf("earlier", "later"), rules.map { it.id })
            assertEquals(listOf(0, 1), rules.map { it.sortOrder })
            assertEquals("", rules.first().subjectTemplate)
            assertEquals("contact", database.dao().ruleLinks("earlier").single().contactId)
            val event = database.dao().event("legacy")!!
            assertEquals(MessageKind.MMS, event.kind)
            assertEquals(EmailMetadata.values().fold(0) { mask, item -> mask or item.bit }, event.emailMetadataMask)
            assertEquals(1, event.attachmentCount)
            assertEquals("legacy@example.com", database.dao().delivery("legacy-job")!!.recipient)
            assertEquals("", database.dao().delivery("legacy-job")!!.emailSubjectSnapshot)
            assertEquals(1, database.dao().attachments("legacy").size)
            assertNotNull(database.dao().contact("contact"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
