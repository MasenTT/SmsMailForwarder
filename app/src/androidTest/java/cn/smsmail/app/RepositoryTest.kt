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
    private var sentSubject = ""
    private var sentBody = ""
    private val sentRecipients = mutableListOf<String>()
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MailApp>()
        context.getSharedPreferences("private_settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        crypto = Crypto(); settings = Settings(context, crypto)
        repo = Repository(context, db, settings, crypto, object : MailGateway {
            override fun send(config: MailConfig, recipient: String, subject: String, body: String): MailResult {
                calls++; sentSubject = subject; sentBody = body; sentRecipients.add(recipient); return result
            }
            override fun send(config: MailConfig, recipient: String, subject: String, body: String, attachments: List<MailAttachment>): MailResult {
                calls++; sentSubject = subject; sentBody = body; sentRecipients.add(recipient); sentAttachments = attachments; return result
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
    @Test fun firstMatchingRuleRoutesOnlyItsDeduplicatedRecipients() = runBlocking {
        db.dao().saveRule(RuleRow("1", "通知", "通知", "a@example.com\nb@example.com", sortOrder = 0))
        db.dao().saveRule(RuleRow("2", "业务", "业务", "A@example.com", sortOrder = 1))
        repo.accept("10086", 2L, "SIM1", "业务通知")
        db.dao().deleteRule("1"); db.dao().deleteRule("2")
        val jobs = db.dao().pending()
        assertEquals(2, jobs.size)
        assertEquals(listOf("a@example.com", "b@example.com"), jobs.map { it.recipient }.sorted())
        assertEquals("通知", db.dao().event(jobs.first().eventId)!!.matchedRules)
        assertFalse(repo.deliver(jobs[0].id))
        result = MailResult(FailureKind.TRANSIENT, "offline")
        assertTrue(repo.deliver(jobs[1].id))
        assertEquals("SENT", db.dao().delivery(jobs[0].id)!!.state)
        assertEquals("PENDING", db.dao().delivery(jobs[1].id)!!.state)
        assertFalse(repo.deliver(jobs[0].id))
        assertEquals(2, calls)
    }

    @Test fun ruleTitleAndBodyOptionsAreSnapshottedWhenMessageIsAccepted() = runBlocking {
        val contact = repo.saveContact(null, "验证码收件人", "otp@example.com", "")
        val rule = RuleRow("otp", "验证码", "验证码", "", subjectTemplate = "{消息类型}：{来源号码}：{匹配规则}：{消息内容}")
        repo.saveRuleWithContacts(rule, listOf(contact.id))
        settings.emailMetadataMask = EmailMetadata.SOURCE_NUMBER.bit or EmailMetadata.MESSAGE_TIME.bit or EmailMetadata.MATCHED_RULE.bit

        repo.accept("10690000", 1000L, "订阅 2", "验证码 123456")
        val event = db.dao().observeEvents().first().single().event
        val pending = db.dao().pending().single()
        assertEquals(EmailMetadata.SOURCE_NUMBER.bit or EmailMetadata.MESSAGE_TIME.bit or EmailMetadata.MATCHED_RULE.bit, event.emailMetadataMask)
        assertEquals("短信：10690000：验证码：验证码 123456", crypto.decrypt(pending.emailSubjectSnapshot))
        assertFalse(pending.emailSubjectSnapshot.contains("10690000"))

        settings.emailMetadataMask = 0
        db.dao().saveRule(rule.copy(subjectTemplate = "编辑后的标题"))
        assertFalse(repo.deliver(pending.id))

        assertEquals("短信：10690000：验证码：验证码 123456", sentSubject)
        assertEquals(
            "来源号码：10690000\n短信时间：${formatTime(1000L)}\n匹配规则：验证码\n\n验证码 123456",
            sentBody
        )
    }

    @Test fun ruleOrderCanBePersistentlyReorderedAndControlsRouting() = runBlocking {
        val first = repo.saveContact(null, "第一个", "first@example.com", "")
        val second = repo.saveContact(null, "第二个", "second@example.com", "")
        repo.saveRuleWithContacts(RuleRow("specific", "验证码", "验证码", ""), listOf(first.id))
        repo.saveRuleWithContacts(RuleRow("catchall", "所有", ".*", ""), listOf(second.id))

        repo.reorderRules(listOf("catchall", "specific"))
        assertEquals(listOf("catchall", "specific"), db.dao().rules().map { it.id })
        repo.accept("10086", 30L, "SIM1", "验证码 123456")

        val job = db.dao().pending().single()
        assertEquals("second@example.com", job.recipient)
        assertEquals("所有", db.dao().event(job.eventId)!!.matchedRules)
    }

    @Test fun metadataDefaultsOffAndCanBeChangedIndependently() = runBlocking {
        assertEquals(0, settings.emailMetadataMask)
        settings.setEmailMetadataVisible(EmailMetadata.SOURCE_NUMBER, true)
        settings.setEmailMetadataVisible(EmailMetadata.SIM_INFO, true)
        assertEquals(EmailMetadata.SOURCE_NUMBER.bit or EmailMetadata.SIM_INFO.bit, settings.emailMetadataMask)
        assertFalse((settings.emailMetadataMask and EmailMetadata.RECEIVED_TIME.bit) != 0)
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
        repo.saveRuleWithContacts(RuleRow("mms-subject", "账单彩信", "电子账单", "", true, 1L, RuleType.CUSTOM.name, RulePresets.VERSION, subjectTemplate = "{彩信主题}：{消息内容}"), listOf(contact.id))
        val attachment = MailAttachment("账单图片.png", "image/png", byteArrayOf(1, 2, 3, 4))
        repo.accept("10690000", 1000L, "SIM1", "本月账单请查收", kind = MessageKind.MMS, subject = "电子账单", attachments = listOf(attachment))

        val event = db.dao().observeEvents().first().single().event
        assertEquals(MessageKind.MMS, event.kind)
        assertEquals("电子账单", crypto.decrypt(event.subject))
        assertNotEquals("账单图片.png", db.dao().attachments(event.id).single().fileName)
        assertArrayEquals(attachment.bytes, crypto.decrypt(db.dao().attachments(event.id).single().content))
        val pending = db.dao().pending().single()
        assertEquals("bill@example.com", pending.recipient)
        assertEquals("电子账单：本月账单请查收", crypto.decrypt(pending.emailSubjectSnapshot))

        assertFalse(repo.deliver(pending.id))
        assertEquals("电子账单：本月账单请查收", sentSubject)
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
