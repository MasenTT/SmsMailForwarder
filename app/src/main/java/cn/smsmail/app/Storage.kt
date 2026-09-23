package cn.smsmail.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import cn.smsmail.core.MailConfig
import cn.smsmail.core.Addresses
import cn.smsmail.core.RulePresets
import cn.smsmail.core.RuleType
import java.util.Locale
import java.util.UUID

class Crypto {
    private companion object { val keyLock = Any() }
    private val alias = "smsmail.private.v1"
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun fingerprintKey(): SecretKey = synchronized(keyLock) {
        val name = "smsmail.fingerprint.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(name, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
    }
    fun fingerprint(contentHash: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(fingerprintKey()) }
        return "h1:" + Base64.encodeToString(mac.doFinal(contentHash.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size >= 28) { "加密记录损坏" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "加密附件损坏" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
}

data class SmsDiagnostic(val receivedAt: Long, val partCount: Int, val bodyLength: Int, val status: String)
data class MmsDiagnostic(val receivedAt: Long, val textPartCount: Int, val attachmentCount: Int, val status: String)

class Settings(context: Context, private val crypto: Crypto) {
    private val prefs = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()); changed() }
    var defaults: String
        get() = prefs.getString("defaults", "")!!
        set(value) { check(prefs.edit().putString("defaults", value).commit()); changed() }
    var lastError: String
        get() = prefs.getString("last_error", "")!!
        set(value) { check(prefs.edit().putString("last_error", value).commit()); changed() }
    fun smsDiagnostic(): SmsDiagnostic = SmsDiagnostic(
        prefs.getLong("sms_diag_at", 0L),
        prefs.getInt("sms_diag_parts", 0),
        prefs.getInt("sms_diag_length", 0),
        prefs.getString("sms_diag_status", "")!!
    )
    fun recordSmsBroadcast(partCount: Int, bodyLength: Int, status: String) {
        check(prefs.edit()
            .putLong("sms_diag_at", System.currentTimeMillis())
            .putInt("sms_diag_parts", partCount)
            .putInt("sms_diag_length", bodyLength)
            .putString("sms_diag_status", status)
            .commit())
        changed()
    }
    fun mmsDiagnostic(): MmsDiagnostic = MmsDiagnostic(
        prefs.getLong("mms_diag_at", 0L), prefs.getInt("mms_diag_text", 0),
        prefs.getInt("mms_diag_attachments", 0), prefs.getString("mms_diag_status", "")!!
    )
    fun recordMmsBroadcast(textParts: Int, attachments: Int, status: String, receivedAt: Long = System.currentTimeMillis()) {
        check(prefs.edit().putLong("mms_diag_at", receivedAt).putInt("mms_diag_text", textParts)
            .putInt("mms_diag_attachments", attachments).putString("mms_diag_status", status).commit())
        changed()
    }
    fun changed() { changes.update { it + 1 } }
    fun mail(): MailConfig? = prefs.getString("mail", null)?.let {
        val json = JSONObject(crypto.decrypt(it))
        MailConfig(json.getString("host"), json.getInt("port"), json.getString("security"), json.getString("email"), json.getString("password"))
    }
    fun saveMail(config: MailConfig) {
        require(config.error() == null) { config.error()!! }
        val json = JSONObject().put("host", config.host).put("port", config.port).put("security", config.security)
            .put("email", config.email).put("password", config.password)
        check(prefs.edit().putString("mail", crypto.encrypt(json.toString())).commit())
        changed()
    }
}

@Entity(tableName = "contacts", indices = [Index(value = ["normalizedEmail"], unique = true)])
data class ContactRow(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    val normalizedEmail: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rules")
data class RuleRow(
    @PrimaryKey val id: String,
    val name: String,
    val expression: String,
    // Kept as a migration-only compatibility column. Runtime routing uses ruleContacts.
    val recipients: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val type: String = RuleType.CUSTOM.name,
    val templateVersion: Int = RulePresets.VERSION
)

@Entity(
    tableName = "rule_contacts",
    primaryKeys = ["ruleId", "contactId"],
    foreignKeys = [
        ForeignKey(entity = RuleRow::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ContactRow::class, parentColumns = ["id"], childColumns = ["contactId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("contactId")]
)
data class RuleContactCrossRef(val ruleId: String, val contactId: String)

@Entity(
    tableName = "default_contacts",
    primaryKeys = ["contactId"],
    foreignKeys = [ForeignKey(entity = ContactRow::class, parentColumns = ["id"], childColumns = ["contactId"], onDelete = ForeignKey.RESTRICT)]
)
data class DefaultContactCrossRef(val contactId: String)

data class RuleWithContacts(
    @Embedded val rule: RuleRow,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = RuleContactCrossRef::class, parentColumn = "ruleId", entityColumn = "contactId")
    ) val contacts: List<ContactRow>
)
@Entity(tableName = "events", indices = [Index(value = ["fingerprint"], unique = true)])
data class EventRow(@PrimaryKey val id: String, val fingerprint: String, val source: String, val body: String, val receivedAt: Long, val smsAt: Long, val sim: String, val matchedRules: String,
                    @ColumnInfo(defaultValue = "'SMS'") val kind: String = MessageKind.SMS,
                    @ColumnInfo(defaultValue = "''") val subject: String = "",
                    @ColumnInfo(defaultValue = "0") val attachmentCount: Int = 0)
@Entity(tableName = "deliveries", foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class DeliveryRow(@PrimaryKey val id: String, val eventId: String, val recipient: String, val state: String = "PENDING", val attempts: Int = 0, val detail: String = "等待发送", val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "event_attachments", foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class AttachmentRow(@PrimaryKey val id: String, val eventId: String, val fileName: String, val contentType: String, val content: ByteArray)

data class EventWithDeliveries(@Embedded val event: EventRow, @Relation(parentColumn = "id", entityColumn = "eventId") val deliveries: List<DeliveryRow>)

@Dao interface MailDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE, createdAt") fun observeContacts(): Flow<List<ContactRow>>
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE, createdAt") suspend fun contacts(): List<ContactRow>
    @Query("SELECT * FROM contacts WHERE id=:id") suspend fun contact(id: String): ContactRow?
    @Upsert suspend fun saveContact(contact: ContactRow)
    @Query("DELETE FROM contacts WHERE id=:id") suspend fun deleteContact(id: String): Int
    @Query("SELECT COUNT(*) FROM rule_contacts WHERE contactId=:id") suspend fun ruleReferenceCount(id: String): Int
    @Query("SELECT COUNT(*) FROM default_contacts WHERE contactId=:id") suspend fun defaultReferenceCount(id: String): Int
    @Query("SELECT * FROM default_contacts") suspend fun defaultLinks(): List<DefaultContactCrossRef>
    @Query("SELECT contacts.* FROM contacts INNER JOIN default_contacts ON contacts.id=default_contacts.contactId ORDER BY contacts.name COLLATE NOCASE, contacts.createdAt") suspend fun defaultContacts(): List<ContactRow>
    @Query("SELECT contacts.* FROM contacts INNER JOIN default_contacts ON contacts.id=default_contacts.contactId ORDER BY contacts.name COLLATE NOCASE, contacts.createdAt") fun observeDefaultContacts(): Flow<List<ContactRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceDefaultLinks(links: List<DefaultContactCrossRef>)
    @Query("DELETE FROM default_contacts") suspend fun clearDefaultLinks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceRuleLinks(links: List<RuleContactCrossRef>)
    @Query("DELETE FROM rule_contacts WHERE ruleId=:ruleId") suspend fun clearRuleLinks(ruleId: String)
    @Query("SELECT * FROM rule_contacts WHERE ruleId=:ruleId") suspend fun ruleLinks(ruleId: String): List<RuleContactCrossRef>
    @Transaction @Query("SELECT * FROM rules ORDER BY createdAt") fun observeRulesWithContacts(): Flow<List<RuleWithContacts>>
    @Transaction @Query("SELECT * FROM rules ORDER BY createdAt") suspend fun rulesWithContacts(): List<RuleWithContacts>
    @Query("SELECT * FROM rules ORDER BY createdAt") fun observeRules(): Flow<List<RuleRow>>
    @Query("SELECT * FROM rules ORDER BY createdAt") suspend fun rules(): List<RuleRow>
    @Upsert suspend fun saveRule(rule: RuleRow)
    @Query("DELETE FROM rules WHERE id=:id") suspend fun deleteRule(id: String)
    @Transaction @Query("SELECT * FROM events ORDER BY receivedAt DESC") fun observeEvents(): Flow<List<EventWithDeliveries>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(event: EventRow): Long
    @Insert suspend fun insertDeliveries(deliveries: List<DeliveryRow>)
    @Insert suspend fun insertAttachments(attachments: List<AttachmentRow>)
    @Query("SELECT * FROM event_attachments WHERE eventId=:eventId ORDER BY id") suspend fun attachments(eventId: String): List<AttachmentRow>
    @Query("SELECT * FROM events WHERE id=:id") suspend fun event(id: String): EventRow?
    @Query("SELECT * FROM deliveries WHERE id=:id") suspend fun delivery(id: String): DeliveryRow?
    @Query("SELECT * FROM deliveries WHERE state='PENDING'") suspend fun pending(): List<DeliveryRow>
    @Query("UPDATE deliveries SET state='SENDING', attempts=attempts+1, detail='正在提交邮件服务器', updatedAt=:now WHERE id=:id AND state='PENDING' AND attempts<5") suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE deliveries SET state=:state, detail=:detail, updatedAt=:now WHERE id=:id") suspend fun finish(id: String, state: String, detail: String, now: Long)
    @Query("UPDATE deliveries SET state='PENDING', attempts=0, detail='等待重试' WHERE id=:id AND state IN ('FAILED','CONFIG_ERROR','UNCERTAIN')") suspend fun retry(id: String): Int
    @Query("UPDATE deliveries SET state='PENDING', attempts=0, detail='配置已更新，等待重试' WHERE state='CONFIG_ERROR'") suspend fun resumeConfigErrors()
    @Query("DELETE FROM events WHERE receivedAt<:before AND NOT EXISTS (SELECT 1 FROM deliveries WHERE eventId=events.id AND state NOT IN ('SENT','FAILED'))") suspend fun clearCompleted(before: Long)
}

// Version 1 stored the unkeyed content hash. Re-key it in-place so upgrades keep
// deduplication and queued deliveries without decrypting or copying SMS bodies.
fun fingerprintMigration(crypto: Crypto): Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, fingerprint FROM events").use { rows ->
            while (rows.moveToNext()) {
                db.execSQL("UPDATE events SET fingerprint=? WHERE id=?",
                    arrayOf(crypto.fingerprint(rows.getString(1)), rows.getString(0)))
            }
        }
    }
}

fun contactMigration(legacyDefaults: String): Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts (id TEXT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, normalizedEmail TEXT NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contacts_normalizedEmail ON contacts (normalizedEmail)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rule_contacts (ruleId TEXT NOT NULL, contactId TEXT NOT NULL, PRIMARY KEY(ruleId, contactId), FOREIGN KEY(ruleId) REFERENCES rules(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(contactId) REFERENCES contacts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rule_contacts_contactId ON rule_contacts (contactId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS default_contacts (contactId TEXT NOT NULL, PRIMARY KEY(contactId), FOREIGN KEY(contactId) REFERENCES contacts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("ALTER TABLE rules ADD COLUMN type TEXT NOT NULL DEFAULT 'CUSTOM'")
        db.execSQL("ALTER TABLE rules ADD COLUMN templateVersion INTEGER NOT NULL DEFAULT 1")

        var generated = 1
        fun contactId(rawEmail: String): String {
            val email = rawEmail.trim()
            val normalized = email.lowercase(Locale.ROOT)
            db.query("SELECT id FROM contacts WHERE normalizedEmail=?", arrayOf(normalized)).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
            val id = UUID.nameUUIDFromBytes(normalized.toByteArray(Charsets.UTF_8)).toString()
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO contacts(id,name,email,normalizedEmail,note,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?)",
                arrayOf(id, "原有联系人 ${generated++}", email, normalized, "由旧版本邮箱自动导入", now, now))
            return id
        }
        db.query("SELECT id, recipients FROM rules").use { cursor ->
            while (cursor.moveToNext()) {
                val ruleId = cursor.getString(0)
                val recipients = runCatching { Addresses.parse(cursor.getString(1)) }.getOrDefault(emptyList())
                recipients.forEach { email -> db.execSQL("INSERT OR IGNORE INTO rule_contacts(ruleId,contactId) VALUES(?,?)", arrayOf(ruleId, contactId(email))) }
            }
        }
        runCatching { Addresses.parse(legacyDefaults) }.getOrDefault(emptyList()).forEach { email ->
            db.execSQL("INSERT OR IGNORE INTO default_contacts(contactId) VALUES(?)", arrayOf(contactId(email)))
        }
    }
}

fun mmsMigration(): Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN kind TEXT NOT NULL DEFAULT 'SMS'")
        db.execSQL("ALTER TABLE events ADD COLUMN subject TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE events ADD COLUMN attachmentCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS event_attachments (id TEXT NOT NULL, eventId TEXT NOT NULL, fileName TEXT NOT NULL, contentType TEXT NOT NULL, content BLOB NOT NULL, PRIMARY KEY(id), FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_event_attachments_eventId ON event_attachments (eventId)")
    }
}

@Database(entities = [ContactRow::class, RuleRow::class, RuleContactCrossRef::class, DefaultContactCrossRef::class, EventRow::class, DeliveryRow::class, AttachmentRow::class], version = 4, exportSchema = true)
abstract class MailDatabase : RoomDatabase() { abstract fun dao(): MailDao }
