package cn.smsmail.app

import android.Manifest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import cn.smsmail.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Rule as JUnitRule
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    @get:JUnitRule val permissions = GrantPermissionRule.grant(Manifest.permission.RECEIVE_SMS)
    private lateinit var db: MailDatabase
    private lateinit var settings: Settings
    private lateinit var repo: Repository
    private lateinit var crypto: Crypto
    private val scheduled = mutableListOf<String>()
    private var result = MailResult(detail = "test accepted")
    private var calls = 0
    private var sentAttachments = emptyList<MailAttachment>()
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MailApp>()
        context.getSharedPreferences("private_settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        crypto = Crypto(); settings = Settings(context, crypto)
        repo = Repository(context, db, settings, crypto, object : MailGateway {
            override fun send(config: MailConfig, recipient: String, subject: String, body: String): MailResult { calls++; return result }
            override fun send(config: MailConfig, recipient: String, subject: String, body: String, attachments: List<MailAttachment>): MailResult {
                calls++; sentAttachments = attachments; return result
            }
        }, { scheduled.add(it) })
        settings.saveMail(MailConfig("smtp.example.com", 465, "SSL", "test@example.com", "fake-test-credential"))
        settings.defaults = "default@example.com"
        settings.enabled = true
    }
    @After fun close() { settings.enabled = false; db.close() }
    @Test fun retentionDeletesOnlyOldTerminalEvents() = runBlocking {
        val now = System.currentTimeMillis()
        val cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(7)
        suspend fun seed(id: String, time: Long, state: String) {
            db.dao().insertEvent(EventRow(id, id, crypto.encrypt("source"), crypto.encrypt("body"), time, time, "SIM", "default"))
            db.dao().insertDeliveries(listOf(DeliveryRow(id, id, "a@example.com", state)))
        }
        seed("old-sent", cutoff - 1, "SENT")
        seed("new-sent", cutoff + 1, "SENT")
        seed("old-pending", cutoff - 1, "PENDING")
        seed("old-uncertain", cutoff - 1, "UNCERTAIN")
        db.dao().clearCompleted(cutoff)
        assertNull(db.dao().event("old-sent"))
        assertNotNull(db.dao().event("new-sent"))
        assertNotNull(db.dao().event("old-pending"))
        assertNotNull(db.dao().event("old-uncertain"))
    }
    @Test fun encryptedStoreAndDuplicateBroadcast() = runBlocking {
        repo.accept("10086", 1L, "SIM1", "测试短信")
        repo.accept("10086", 1L, "SIM1", "测试短信")
        val rows = db.dao().observeEvents().first()
        assertEquals(1, rows.size)
        assertNotEquals(Fingerprints.sms("10086", 1L, "SIM1", "测试短信"), rows.single().event.fingerprint)
        assertNotEquals("测试短信", rows.single().event.body)
        assertEquals("测试短信", repo.decrypt(rows.single().event.body))
        assertEquals(1, scheduled.size)
    }
    @Test fun multipleRulesSnapshotAndPartialSuccess() = runBlocking {
        db.dao().saveRule(RuleRow("1", "通知", "通知", "a@example.com\nb@example.com"))
        db.dao().saveRule(RuleRow("2", "业务", "业务", "A@example.com"))
        repo.accept("10086", 2L, "SIM1", "业务通知")
        db.dao().deleteRule("1"); db.dao().deleteRule("2")
        val jobs = db.dao().pending()
        assertEquals(2, jobs.size)
        assertFalse(repo.deliver(jobs[0].id))
        result = MailResult(FailureKind.TRANSIENT, "offline")
        assertTrue(repo.deliver(jobs[1].id))
        assertEquals("SENT", db.dao().delivery(jobs[0].id)!!.state)
        assertEquals("PENDING", db.dao().delivery(jobs[1].id)!!.state)
        assertFalse(repo.deliver(jobs[0].id))
        assertEquals(2, calls)
    }
    @Test fun pauseAndResumeAndFiveAttemptLimit() = runBlocking {
        repo.accept("10086", 3L, "SIM1", "短信")
        val id = db.dao().pending().single().id
        settings.enabled = false
        assertFalse(repo.deliver(id)); assertEquals(0, calls)
        repo.accept("10086", 4L, "SIM1", "暂停期间")
        assertEquals(1, db.dao().observeEvents().first().size)
        settings.enabled = true
        result = MailResult(FailureKind.TRANSIENT, "offline")
        repeat(4) { assertTrue(repo.deliver(id)) }
        assertFalse(repo.deliver(id))
        assertEquals(5, calls)
        assertEquals("FAILED", db.dao().delivery(id)!!.state)
        repo.retry(id)
        assertEquals(0, db.dao().delivery(id)!!.attempts)
    }
    @Test fun uncertainInterruptedAndConfigRecovery() = runBlocking {
        repo.accept("10086", 5L, "SIM1", "短信")
        val id = db.dao().pending().single().id
        db.dao().claim(id, 1L)
        assertFalse(repo.deliver(id))
        assertEquals("UNCERTAIN", db.dao().delivery(id)!!.state)
        assertEquals(0, calls)
        repo.retry(id)
        result = MailResult(FailureKind.CONFIG, "auth")
        repo.deliver(id)
        assertEquals("CONFIG_ERROR", db.dao().delivery(id)!!.state)
        repo.saveMail(settings.mail()!!)
        assertEquals("PENDING", db.dao().delivery(id)!!.state)
    }
    @Test fun clearPreservesUnfinishedAndCryptoUsesRandomIv() = runBlocking {
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
        repo.accept("10086", 6L, "SIM1", "保留")
        db.dao().clearCompleted(Long.MAX_VALUE)
        assertEquals(1, db.dao().observeEvents().first().size)
        repo.deliver(db.dao().pending().single().id)
        db.dao().clearCompleted(Long.MAX_VALUE)
        assertTrue(db.dao().observeEvents().first().isEmpty())
    }

    @Test fun contactsDriveRulesAndEditedEmailOnlyAffectsNewSms() = runBlocking {
        val first = repo.saveContact(null, "张三", "zhang@example.com", "负责人")
        val second = repo.saveContact(null, "李四", "li@example.com", "备用")
        repo.saveDefaultContacts(listOf(second.id))
        val rule = RuleRow("contact-rule", "验证码", RulePresets.expression(RuleType.OTP), "", true, 1L, RuleType.OTP.name, RulePresets.VERSION)
        repo.saveRuleWithContacts(rule, listOf(first.id))

        repo.accept("10086", 101L, "SIM1", "您的验证码为 123456")
        val oldJob = db.dao().pending().single()
        assertEquals("zhang@example.com", oldJob.recipient)

        repo.saveContact(first.id, "张三", "new@example.com", "已更新")
        repo.accept("10086", 102L, "SIM1", "您的验证码为 654321")
        val recipients = db.dao().pending().map { it.recipient }.sorted()
        assertEquals(listOf("new@example.com", "zhang@example.com"), recipients)
        assertEquals("li@example.com", db.dao().defaultContacts().single().email)
    }

    @Test fun contactDeletionIsBlockedWhileReferencedAndAllowedAfterRemoval() = runBlocking {
        val contact = repo.saveContact(null, "联系人", "contact@example.com", "")
        repo.saveDefaultContacts(listOf(contact.id))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.deleteContact(contact.id) } }
        db.dao().clearDefaultLinks()
        repo.deleteContact(contact.id)
        assertNull(db.dao().contact(contact.id))
    }

    @Test fun mmsSubjectRoutesAndEncryptedAttachmentsSurviveDelivery() = runBlocking {
        val contact = repo.saveContact(null, "账单联系人", "bill@example.com", "")
        repo.saveRuleWithContacts(RuleRow("mms-subject", "账单彩信", "电子账单", "", true, 1L, RuleType.CUSTOM.name, RulePresets.VERSION), listOf(contact.id))
        val attachment = MailAttachment("账单图片.png", "image/png", byteArrayOf(1, 2, 3, 4))
        repo.accept("10690000", 1000L, "SIM1", "本月账单请查收", kind = MessageKind.MMS, subject = "电子账单", attachments = listOf(attachment))

        val event = db.dao().observeEvents().first().single().event
        assertEquals(MessageKind.MMS, event.kind)
        assertEquals("电子账单", crypto.decrypt(event.subject))
        assertNotEquals("账单图片.png", db.dao().attachments(event.id).single().fileName)
        assertArrayEquals(attachment.bytes, crypto.decrypt(db.dao().attachments(event.id).single().content))
        assertEquals("bill@example.com", db.dao().pending().single().recipient)

        assertFalse(repo.deliver(db.dao().pending().single().id))
        assertEquals("账单图片.png", sentAttachments.single().fileName)
        assertEquals("image/png", sentAttachments.single().contentType)
        assertArrayEquals(attachment.bytes, sentAttachments.single().bytes)
    }

    @Test fun mmsWithoutRuleMatchFallsBackToDefaultAndDuplicateIsIgnored() = runBlocking {
        val attachment = MailAttachment("photo.jpg", "image/jpeg", byteArrayOf(5, 6, 7))
        repeat(2) {
            repo.accept("10690000", 2000L, "SIM1", "仅供查看", kind = MessageKind.MMS, subject = "通知", attachments = listOf(attachment))
        }
        val events = db.dao().observeEvents().first()
        assertEquals(1, events.size)
        assertEquals("default@example.com", db.dao().pending().single().recipient)
        assertEquals(1, db.dao().attachments(events.single().event.id).size)
    }

    @Test fun smsDiagnosticStoresOnlyBroadcastMetadata() = runBlocking {
        settings.recordSmsBroadcast(2, 18, "已解析短信广播")
        val diagnostic = settings.smsDiagnostic()
        assertTrue(diagnostic.receivedAt > 0)
        assertEquals(2, diagnostic.partCount)
        assertEquals(18, diagnostic.bodyLength)
        assertEquals("已解析短信广播", diagnostic.status)
    }

    @Test fun smsDiagnosticReportIncludesBuildAndRedactsPrivateValues() = runBlocking {
        settings.recordSmsBroadcast(1, 6, "已解析短信广播")
        val report = buildSmsDiagnosticReport(
            ApplicationProvider.getApplicationContext(), repo, true, settings.smsDiagnostic()
        )
        assertTrue(report.contains("Android："))
        assertTrue(report.contains("系统构建："))
        assertTrue(report.contains("安全补丁："))
        assertTrue(report.contains("短信 AppOps："))
        assertFalse(report.contains("default@example.com"))
        assertFalse(report.contains("fake-test-credential"))
        assertFalse(report.contains("测试短信"))
    }
}
