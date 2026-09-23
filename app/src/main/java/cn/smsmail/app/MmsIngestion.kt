package cn.smsmail.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val MMS_NOTIFICATION_IND = 130
private const val MMS_RETRIEVE_CONF = 132
private const val MMS_ADDRESS_FROM = 137
private const val MAX_MMS_ATTACHMENT_BYTES = 15 * 1024 * 1024

data class DownloadedMms(
    val source: String,
    val receivedAt: Long,
    val sim: String,
    val subject: String,
    val body: String,
    val textPartCount: Int,
    val attachments: List<MailAttachment>
)

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) return
        val receivedAt = System.currentTimeMillis()
        val transactionId = intent.getIntExtra("transactionId", -1)
        val slot = when {
            intent.hasExtra("slot") -> "卡槽 ${intent.getIntExtra("slot", -1) + 1}"
            intent.hasExtra("subscription") -> "订阅 ${intent.getLongExtra("subscription", -1L)}"
            else -> "系统未提供"
        }
        val settings = context.mailApp.settings
        if (!settings.enabled) {
            settings.recordMmsBroadcast(0, 0, "彩信通知已收到，但转发已暂停", receivedAt)
            return
        }
        settings.recordMmsBroadcast(0, 0, "已收到彩信通知，等待系统短信应用下载", receivedAt)
        val request = OneTimeWorkRequestBuilder<MmsIngestWorker>()
            .setInputData(workDataOf(MmsIngestWorker.INPUT_RECEIVED_AT to receivedAt, MmsIngestWorker.INPUT_SIM to slot))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("mms-ingest-$receivedAt-$transactionId", ExistingWorkPolicy.KEEP, request)
    }
}

class MmsIngestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.mailApp
        val receivedAt = inputData.getLong(INPUT_RECEIVED_AT, 0L)
        val sim = inputData.getString(INPUT_SIM).orEmpty().ifBlank { "系统未提供" }
        if (receivedAt <= 0L || !app.settings.enabled) return@withContext Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            app.settings.recordMmsBroadcast(0, 0, "缺少短信库读取权限，未读取彩信内容", receivedAt)
            app.settings.lastError = "彩信读取需要短信库权限；请在设置中申请 READ_SMS，若系统拒绝未知来源应用，需按系统提示解除限制。"
            return@withContext Result.failure()
        }
        try {
            val reader = MmsProviderReader(applicationContext)
            val message = reader.awaitDownloaded(receivedAt)
            if (message == null) {
                if (runAttemptCount < MAX_RETRIES) return@withContext Result.retry()
                app.settings.recordMmsBroadcast(0, 0, "通知已收到，但默认短信应用未下载彩信", receivedAt)
                app.settings.lastError = "彩信通知已收到，但彩信尚未下载到系统短信库。请开启默认短信应用的彩信自动下载，并确认网络和移动数据可用。"
                return@withContext Result.failure()
            }
            app.repository.accept(message.source, message.receivedAt, message.sim.ifBlank { sim }, message.body,
                kind = MessageKind.MMS, subject = message.subject, attachments = message.attachments)
            app.settings.recordMmsBroadcast(message.textPartCount,
                message.attachments.size, "彩信正文和附件已进入邮件队列", receivedAt)
            Result.success()
        } catch (_: SecurityException) {
            app.settings.recordMmsBroadcast(0, 0, "系统拒绝读取彩信短信库", receivedAt)
            app.settings.lastError = "系统拒绝读取彩信内容。请确认 READ_SMS 已获授；小米系统可能还需解除未知来源应用的敏感权限限制。"
            Result.failure()
        } catch (e: Exception) {
            Log.e("SmsMailForwarder", "MMS processing failed: ${e.javaClass.simpleName}")
            app.settings.recordMmsBroadcast(0, 0, "彩信内容读取失败", receivedAt)
            app.settings.lastError = "彩信内容读取失败；请检查彩信是否已下载、系统短信库权限和本机存储空间。"
            Result.failure()
        }
    }

    companion object {
        const val INPUT_RECEIVED_AT = "mms_received_at"
        const val INPUT_SIM = "mms_sim"
        private const val MAX_RETRIES = 4
    }
}

private class MmsProviderReader(context: Context) {
    private val resolver = context.contentResolver

    suspend fun awaitDownloaded(receivedAt: Long): DownloadedMms? {
        repeat(10) {
            val candidate = findCandidate(receivedAt)
            if (candidate != null) {
                if (candidate.second == MMS_RETRIEVE_CONF) return readMessage(candidate.first, candidate.third)
                if (candidate.second == MMS_NOTIFICATION_IND) {
                    val downloaded = waitForSameMessage(candidate.first)
                    if (downloaded != null) return readMessage(downloaded.first, downloaded.third)
                }
            }
            delay(3_000)
        }
        return null
    }

    private suspend fun waitForSameMessage(id: Long): Triple<Long, Int, Long>? {
        repeat(12) {
            val row = queryMessage(id)
            if (row?.second == MMS_RETRIEVE_CONF) return row
            delay(3_000)
        }
        return null
    }

    private fun findCandidate(receivedAt: Long): Triple<Long, Int, Long>? {
        val atSeconds = receivedAt / 1000
        val uri = Telephony.Mms.CONTENT_URI
        val projection = arrayOf(BaseColumns._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_TYPE)
        val selection = "${Telephony.Mms.MESSAGE_BOX}=? AND ${Telephony.Mms.MESSAGE_TYPE} IN (?,?) AND ${Telephony.Mms.DATE} BETWEEN ? AND ?"
        val start = (atSeconds - 20).coerceAtLeast(0)
        val end = atSeconds + 180
        return resolver.query(uri, projection, selection,
            arrayOf(Telephony.Mms.MESSAGE_BOX_INBOX.toString(), MMS_NOTIFICATION_IND.toString(), MMS_RETRIEVE_CONF.toString(), start.toString(), end.toString()),
            "${Telephony.Mms.DATE} DESC")?.use { cursor ->
            var closest: Triple<Long, Int, Long>? = null
            var difference = Long.MAX_VALUE
            while (cursor.moveToNext()) {
                val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE))
                val delta = kotlin.math.abs(date - atSeconds)
                if (delta < difference) {
                    difference = delta
                    closest = Triple(cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE)), date)
                }
            }
            closest
        }
    }

    private fun queryMessage(id: Long): Triple<Long, Int, Long>? = resolver.query(
        Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString()),
        arrayOf(BaseColumns._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_TYPE), null, null, null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else Triple(cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE)), cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)))
    }

    private fun readMessage(id: Long, dateSeconds: Long): DownloadedMms {
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())
        var subject = ""
        var subscriptionId = -1L
        resolver.query(uri, arrayOf(Telephony.Mms.SUBJECT, Telephony.Mms.SUBSCRIPTION_ID), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                subject = cursor.getStringOrNull(Telephony.Mms.SUBJECT).orEmpty()
                subscriptionId = cursor.getLongOrNull(Telephony.Mms.SUBSCRIPTION_ID) ?: -1L
            }
        }
        val sender = readSender(id).orEmpty().ifBlank { "未知彩信来源" }
        val bodyParts = mutableListOf<String>()
        val attachments = mutableListOf<MailAttachment>()
        var totalBytes = 0
        val projection = arrayOf(BaseColumns._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME,
            Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.SEQ)
        resolver.query(Uri.parse("content://mms/part"), projection, "${Telephony.Mms.Part.MSG_ID}=?", arrayOf(id.toString()),
            "${Telephony.Mms.Part.SEQ} ASC")?.use { cursor ->
            var attachmentNumber = 1
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                val type = cursor.getStringOrNull(Telephony.Mms.Part.CONTENT_TYPE).orEmpty().substringBefore(';').trim().ifBlank { "application/octet-stream" }
                if (type.equals("application/smil", ignoreCase = true)) continue
                if (type.equals("text/plain", ignoreCase = true) || type.equals("text/html", ignoreCase = true)) {
                    val text = cursor.getStringOrNull(Telephony.Mms.Part.TEXT).orEmpty()
                    if (text.isNotBlank()) bodyParts += if (type.equals("text/html", true)) android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else text
                    continue
                }
                val bytes = readPart(partId, MAX_MMS_ATTACHMENT_BYTES - totalBytes)
                totalBytes += bytes.size
                val providerName = cursor.getStringOrNull(Telephony.Mms.Part.NAME)
                    ?: cursor.getStringOrNull(Telephony.Mms.Part.FILENAME)
                    ?: ""
                val fileName = providerName.ifBlank { attachmentName(attachmentNumber++, type) }
                attachments += MailAttachment(fileName, type, bytes)
            }
        }
        val text = bodyParts.joinToString("\n").trim()
        return DownloadedMms(sender, dateSeconds * 1000, if (subscriptionId >= 0) "订阅 $subscriptionId" else "", subject, text, bodyParts.size, attachments)
    }

    private fun readSender(messageId: Long): String? {
        val addressUri = Uri.withAppendedPath(Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, messageId.toString()), "addr")
        return resolver.query(addressUri, arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            "${Telephony.Mms.Addr.TYPE}=?", arrayOf(MMS_ADDRESS_FROM.toString()), null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(Telephony.Mms.Addr.ADDRESS) else null
        }
    }

    private fun readPart(partId: Long, remainingLimit: Int): ByteArray {
        require(remainingLimit > 0) { "彩信附件总大小超过 15 MiB" }
        val uri = Uri.withAppendedPath(Uri.parse("content://mms/part"), partId.toString())
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= remainingLimit) { "彩信附件总大小超过 15 MiB" }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalStateException("系统未提供彩信附件数据")
        return output.toByteArray()
    }

    private fun attachmentName(number: Int, mimeType: String): String {
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return "彩信附件-$number" + extension?.let { ".$it" }.orEmpty()
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }
}
