package cn.smsmail.app

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import cn.smsmail.core.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MailApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val crypto by lazy { Crypto() }
    val settings by lazy { Settings(this, crypto) }
    val database by lazy { Room.databaseBuilder(this, MailDatabase::class.java, "smsmail.db")
        .addMigrations(fingerprintMigration(crypto), contactMigration(settings.defaults)).build() }
    val repository by lazy { Repository(this, database, settings, crypto, SmtpGateway()) }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("record-retention", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build())
    }
}
val Context.mailApp get() = applicationContext as MailApp
fun formatTime(time: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))

class Repository(private val context: Context, private val db: MailDatabase, val settings: Settings,
                 private val crypto: Crypto, private val gateway: MailGateway, private val enqueueOverride: ((String) -> Unit)? = null) {
    val dao = db.dao()
    val contacts = dao.observeContacts()
    val defaultContacts = dao.observeDefaultContacts()
    fun hasSmsPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { "当前设备不支持电话短信接收" }
            require(hasSmsPermission()) { "请先授予接收短信权限" }
            val config = settings.mail() ?: error("请先配置发件邮箱")
            require(config.error() == null) { config.error()!! }
            Addresses.parse(settings.defaults)
        }
        settings.enabled = enabled
        if (enabled) resume()
    }
    suspend fun saveMail(config: MailConfig) {
        settings.saveMail(config)
        dao.resumeConfigErrors()
        if (settings.enabled) resume()
    }
    suspend fun resume() { dao.pending().forEach { schedule(it.id) } }
    suspend fun saveContact(id: String?, name: String, email: String, note: String): ContactRow {
        val values = ContactValues.validate(name, email, note)
        val existing = dao.contacts().firstOrNull { it.normalizedEmail == values.normalizedEmail && it.id != id }
        require(existing == null) { "该邮箱已由联系人“${existing!!.name}”维护" }
        val now = System.currentTimeMillis()
        val previous = id?.let { dao.contact(it) }
        val row = ContactRow(id ?: UUID.randomUUID().toString(), values.name, values.email, values.normalizedEmail, values.note,
            previous?.createdAt ?: now, now)
        dao.saveContact(row)
        return row
    }
    suspend fun deleteContact(id: String) {
        require(dao.ruleReferenceCount(id) == 0 && dao.defaultReferenceCount(id) == 0) { "该联系人仍被规则或默认收件人使用，请先移除引用" }
        require(dao.deleteContact(id) > 0) { "联系人不存在" }
    }
    suspend fun saveDefaultContacts(ids: List<String>) {
        val unique = ids.distinct()
        val known = dao.contacts().map { it.id }.toSet()
        require(unique.isNotEmpty()) { "请至少选择一个默认联系人" }
        require(unique.all { it in known }) { "所选联系人已不存在，请重新选择" }
        db.withTransaction {
            dao.clearDefaultLinks()
            dao.replaceDefaultLinks(unique.map(::DefaultContactCrossRef))
        }
    }
    suspend fun saveRuleWithContacts(rule: RuleRow, contactIds: List<String>) {
        val unique = contactIds.distinct()
        val known = dao.contacts().associateBy { it.id }
        require(unique.isNotEmpty()) { "请至少选择一个收件联系人" }
        require(unique.all { it in known }) { "所选联系人已不存在，请重新选择" }
        val addresses = unique.map { known.getValue(it).email }
        db.withTransaction {
            dao.saveRule(rule.copy(recipients = addresses.joinToString("\n")))
            dao.clearRuleLinks(rule.id)
            dao.replaceRuleLinks(unique.map { RuleContactCrossRef(rule.id, it) })
        }
    }
    fun schedule(id: String) {
        enqueueOverride?.let { it(id); return }
        val request = OneTimeWorkRequestBuilder<SendWorker>().setInputData(workDataOf("delivery" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("send-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    suspend fun accept(source: String, smsAt: Long, sim: String, body: String) {
        if (!settings.enabled || !hasSmsPermission()) return
        val ruleRows = dao.rulesWithContacts().map {
            val type = runCatching { RuleType.valueOf(it.rule.type) }.getOrDefault(RuleType.CUSTOM)
            val expression = RulePresets.expression(type, it.rule.expression.takeIf { type == RuleType.CUSTOM })
            val recipients = it.contacts.map { contact -> contact.email }.ifEmpty { runCatching { Addresses.parse(it.rule.recipients) }.getOrDefault(emptyList()) }
            Rule(it.rule.id, it.rule.name, expression, recipients, it.rule.enabled)
        }
        val defaults = dao.defaultContacts().map { it.email }.ifEmpty { runCatching { Addresses.parse(settings.defaults) }.getOrDefault(emptyList()) }
        val route = Router.route(body, ruleRows, defaults)
        val eventId = UUID.randomUUID().toString()
        val rows = route.recipients.map { DeliveryRow(UUID.randomUUID().toString(), eventId, it) }
        val inserted = db.withTransaction {
            val result = dao.insertEvent(EventRow(eventId, crypto.fingerprint(Fingerprints.sms(source, smsAt, sim, body)), crypto.encrypt(source), crypto.encrypt(body),
                System.currentTimeMillis(), smsAt, sim, route.ruleNames.joinToString("、").ifBlank { "默认收件人" }))
            if (result != -1L) dao.insertDeliveries(rows)
            result != -1L
        }
        if (inserted) rows.forEach { schedule(it.id) }
        settings.lastError = if (inserted && rows.isEmpty()) {
            "短信已收到，但没有可用收件人；请设置默认联系人或为命中规则选择联系人"
        } else ""
    }
    fun decrypt(cipher: String): String = crypto.decrypt(cipher)
    suspend fun retry(id: String) {
        require(settings.enabled) { "请先开启转发，再重试" }
        if (dao.retry(id) > 0) schedule(id)
    }
    suspend fun deliver(id: String): Boolean {
        if (!settings.enabled) return false
        val existing = dao.delivery(id) ?: return false
        if (existing.state == "SENDING") {
            dao.finish(id, "UNCERTAIN", "上次发送中断，可能已送出；请核实后手动重试", System.currentTimeMillis())
            return false
        }
        val config = try { settings.mail() } catch (_: Exception) { null }
        if (config == null || config.error() != null) {
            if (existing.state == "PENDING") dao.finish(id, "CONFIG_ERROR", "发件配置不可用，请重新保存邮箱设置", System.currentTimeMillis())
            return false
        }
        if (!settings.enabled) return false
        if (dao.claim(id, System.currentTimeMillis()) == 0) return false
        val current = dao.delivery(id) ?: return false
        val event = dao.event(current.eventId) ?: return false
        val result = try {
            val source = crypto.decrypt(event.source)
            gateway.send(config, current.recipient, "短信转发 | $source | ${formatTime(event.receivedAt)}",
                "来源号码：$source\n接收时间：${formatTime(event.receivedAt)}\n短信时间：${formatTime(event.smsAt)}\nSIM 信息：${event.sim}\n匹配规则：${event.matchedRules}\n\n${crypto.decrypt(event.body)}")
        } catch (_: Exception) { MailResult(FailureKind.PERMANENT, "无法读取加密短信，请检查本机数据") }
        val state = result.kind?.let { DeliveryPolicy.afterFailure(it, current.attempts) } ?: DeliveryState.SENT
        dao.finish(id, state.name, result.detail, System.currentTimeMillis())
        return state == DeliveryState.PENDING
    }
    suspend fun testMail(recipient: String): MailResult = withContext(Dispatchers.IO) {
        val recipients = Addresses.parse(recipient)
        require(recipients.size == 1) { "测试时请填写一个收件邮箱" }
        val config = settings.mail() ?: error("请先保存发件配置")
        gateway.send(config, recipients.single(), "短信转邮件 · 连接测试", "这是一封由你主动发起的配置测试邮件。收到此邮件表示当前发件配置可用。")
    }
}

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsMailForwarder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_REJECTED_ACTION) {
            val result = intent.getIntExtra("result", -1)
            Log.w(TAG, "SMS_REJECTED result=$result")
            context.mailApp.settings.recordSmsBroadcast(0, 0, "系统拒收短信（result=$result）")
            context.mailApp.settings.lastError = "系统拒收最近一条短信（result=$result），请检查短信拦截、SIM 状态和系统权限限制"
            return
        }
        val isTextSms = intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        val isDataSms = intent.action == Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION
        if (!isTextSms && !isDataSms) return
        val broadcastName = if (isDataSms) "DATA_SMS_RECEIVED" else "SMS_RECEIVED"
        val pending = goAsync()
        context.mailApp.scope.launch {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
                if (messages.isNotEmpty()) {
                    val body = messages.joinToString("") { it.messageBody.orEmpty() }
                    val status = when {
                        body.isBlank() -> "已收到${if (isDataSms) "数据短信" else "短信"}广播，但正文为空"
                        isDataSms -> "已解析数据短信广播"
                        else -> "已解析短信广播"
                    }
                    context.mailApp.settings.recordSmsBroadcast(messages.size, body.length, status)
                    val sim = when {
                        intent.hasExtra("subscription") -> "订阅 ${intent.getIntExtra("subscription", -1)}"
                        intent.hasExtra("slot") -> "卡槽 ${intent.getIntExtra("slot", -1) + 1}"
                        else -> "系统未提供"
                    }
                    Log.i(TAG, "$broadcastName parsed parts=${messages.size} bodyLength=${body.length} sim=$sim enabled=${context.mailApp.settings.enabled} permission=${context.mailApp.repository.hasSmsPermission()}")
                    context.mailApp.repository.accept(messages.first().originatingAddress ?: "未知号码", messages.first().timestampMillis,
                        sim, body)
                } else {
                    Log.w(TAG, "$broadcastName received without parseable message parts")
                    context.mailApp.settings.recordSmsBroadcast(0, 0, "收到${if (isDataSms) "数据短信" else "短信"}广播，但没有可解析的短信片段")
                }
            } catch (_: Exception) {
                Log.e(TAG, "$broadcastName parsing failed")
                context.mailApp.settings.recordSmsBroadcast(0, 0, "短信广播解析失败")
                context.mailApp.settings.lastError = "最近一次短信处理失败，请检查配置和存储空间"
            } finally { pending.finish() }
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        context.mailApp.scope.launch {
            try { if (context.mailApp.settings.enabled) context.mailApp.repository.resume() }
            finally { pending.finish() }
        }
    }
}
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("delivery") ?: return@withContext Result.failure()
        try { if (applicationContext.mailApp.repository.deliver(id)) Result.retry() else Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.mailApp.database.dao().clearCompleted(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        return Result.success()
    }
}
