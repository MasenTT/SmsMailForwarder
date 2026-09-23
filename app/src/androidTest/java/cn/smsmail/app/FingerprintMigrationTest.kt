package cn.smsmail.app

import android.Manifest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cn.smsmail.core.Fingerprints
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class FingerprintMigrationTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECEIVE_SMS)

    @Test fun upgradePreservesSmsQueueAndDuplicateDetection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MailApp>()
        val name = "fingerprint-migration-test.db"
        context.deleteDatabase(name)
        val crypto = Crypto()
        val raw = Fingerprints.sms("10086", 1L, "SIM1", "迁移验证码123456")
        val encryptedBody = crypto.encrypt("迁移验证码123456")
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("cn.smsmail.app.MailDatabase/1.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
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
            old.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any>("legacy", raw, crypto.encrypt("10086"), encryptedBody, 10L, 1L, "SIM1", "默认收件人"))
            old.execSQL("INSERT INTO deliveries VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any>("legacy-job", "legacy", "default@example.com", "PENDING", 2, "等待重试", 10L))
            old.version = 1
        }
        val database = Room.databaseBuilder(context, MailDatabase::class.java, name)
            .addMigrations(fingerprintMigration(crypto), contactMigration("default@example.com"), mmsMigration()).build()
        val settings = Settings(context, crypto)
        val previousEnabled = settings.enabled
        val previousDefaults = settings.defaults
        try {
            val event = database.dao().event("legacy")!!
            assertEquals(crypto.fingerprint(raw), event.fingerprint)
            assertNotEquals(raw, event.fingerprint)
            assertEquals(encryptedBody, event.body)
            assertEquals("迁移验证码123456", crypto.decrypt(event.body))
            val delivery = database.dao().delivery("legacy-job")!!
            assertEquals("PENDING", delivery.state)
            assertEquals("default@example.com", database.dao().defaultContacts().single().email)
            assertEquals(2, delivery.attempts)
            settings.enabled = true
            settings.defaults = "default@example.com"
            val scheduled = mutableListOf<String>()
            val repository = Repository(context, database, settings, crypto, SmtpGateway(), { scheduled.add(it) })
            repository.accept("10086", 1L, "SIM1", "迁移验证码123456")
            assertEquals(1, database.dao().observeEvents().first().size)
            assertTrue(scheduled.isEmpty())
            repository.accept("10086", 2L, "SIM1", "另一条短信")
            assertEquals(2, database.dao().observeEvents().first().size)
            assertEquals(1, scheduled.size)
        } finally {
            settings.enabled = previousEnabled
            settings.defaults = previousDefaults
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun keyedFingerprintIsStableAcrossInstancesAndKeyIsNotExportable() {
        val hash = Fingerprints.sms("10086", 1L, "SIM1", "验证码123456")
        val first = Crypto().fingerprint(hash)
        assertEquals(first, Crypto().fingerprint(hash))
        assertNotEquals(first, Crypto().fingerprint(Fingerprints.sms("10086", 1L, "SIM1", "验证码123457")))
        assertNotEquals(hash, first)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey("smsmail.fingerprint.v1", null).encoded)
    }
}
