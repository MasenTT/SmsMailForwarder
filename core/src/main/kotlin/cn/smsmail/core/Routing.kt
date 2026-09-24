package cn.smsmail.core

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.security.MessageDigest
import java.util.Locale

data class Rule(
    val id: String,
    val name: String,
    val expression: String,
    val recipients: List<String>,
    val enabled: Boolean = true,
    val subjectTemplate: String = ""
)
data class Route(val recipients: List<String>, val ruleNames: List<String>, val matchedRule: Rule? = null)
enum class RuleType { ALL, OTP, AMOUNT, CUSTOM }
object RulePresets {
    const val VERSION = 1
    private const val ALL = "(?s).*"
    private const val OTP = "(?i)(验证码|校验码|动态码|动态密码|动态口令|一次性密码|\\bOTP\\b|verification\\s+code|security\\s+code)"
    private const val AMOUNT = "(?:¥|￥|人民币|RMB|CNY)\\s*[-+]?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|[-+]?\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:元|人民币|RMB|CNY|¥|￥)"
    fun expression(type: RuleType, custom: String? = null): String = when (type) {
        RuleType.ALL -> ALL
        RuleType.OTP -> OTP
        RuleType.AMOUNT -> AMOUNT
        RuleType.CUSTOM -> custom.orEmpty()
    }
    fun description(type: RuleType): String = when (type) {
        RuleType.ALL -> "所有已接收短信"
        RuleType.OTP -> "验证码、校验码、动态口令或 OTP"
        RuleType.AMOUNT -> "带人民币金额、元、¥、￥、RMB 或 CNY 的数字"
        RuleType.CUSTOM -> "自定义 RE2 正则"
    }
}
data class ContactValues(val name: String, val email: String, val normalizedEmail: String, val note: String) {
    companion object {
        fun validate(name: String, email: String, note: String): ContactValues {
            val cleanName = name.trim()
            require(cleanName.length in 1..40) { "联系人姓名长度需为 1 到 40 个字符" }
            val parsed = Addresses.parse(email.trim())
            require(parsed.size == 1) { "联系人只能填写一个邮箱地址" }
            val cleanEmail = parsed.single()
            val cleanNote = note.trim()
            require(cleanNote.length <= 200) { "备注不能超过 200 个字符" }
            return ContactValues(cleanName, cleanEmail, cleanEmail.lowercase(Locale.ROOT), cleanNote)
        }
    }
}
object Addresses {
    private val address = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$")
    fun parse(input: String): List<String> {
        val parts = input.split(Regex("[,;；，\\r\\n]+" )).map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "请填写至少一个收件邮箱" }
        require(parts.all { it.length <= 254 && address.matches(it) && !it.substringBefore('@').startsWith('.') && !it.substringBefore('@').endsWith('.') && !it.substringBefore('@').contains("..") }) { "邮箱格式不正确，请使用纯邮箱地址，多个地址用换行或逗号分隔" }
        return distinct(parts)
    }
    fun distinct(addresses: List<String>) = addresses.distinctBy { it.trim().lowercase(Locale.ROOT) }.map { it.trim() }
}
object Router {
    fun regexError(expression: String): String? {
        if (expression.isBlank()) return "请填写正则表达式"
        if (expression.length > 4096) return "正则表达式不能超过 4096 个字符"
        return try { Pattern.compile(expression); null } catch (_: PatternSyntaxException) { "正则表达式无效或包含 RE2 不支持的语法" }
    }
    fun matches(expression: String, body: String): Boolean = Pattern.compile(expression).matcher(body).find()
    fun route(body: String, rules: List<Rule>, defaults: List<String>): Route {
        val matched = rules.firstOrNull { it.enabled && matches(it.expression, body) }
        return if (matched == null) Route(Addresses.distinct(defaults), emptyList())
        else Route(Addresses.distinct(matched.recipients), listOf(matched.name), matched)
    }
}

object SubjectTemplate {
    const val MESSAGE_CONTENT_TOKEN = "{消息内容}"
    const val MESSAGE_CONTENT_PREVIEW_LIMIT = 50
    private val tokenPattern = Regex("\\{[^{}]+\\}")
    private val messageContentSeparators = Regex("[\\s\\p{Z}\\p{Cc}]+")

    fun render(template: String, values: Map<String, String>): String =
        tokenPattern.replace(template) { token ->
            val value = values[token.value] ?: return@replace token.value
            if (token.value == MESSAGE_CONTENT_TOKEN) messageContentPreview(value) else value
        }.trim()

    private fun messageContentPreview(content: String): String {
        val normalized = content.replace(messageContentSeparators, " ").trim()
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount <= MESSAGE_CONTENT_PREVIEW_LIMIT) return normalized

        val previewEnd = normalized.offsetByCodePoints(0, MESSAGE_CONTENT_PREVIEW_LIMIT - 1)
        return normalized.substring(0, previewEnd) + "…"
    }
}
data class MailConfig(val host: String, val port: Int, val security: String, val email: String, val password: String) {
    override fun toString() = "MailConfig(host=$host, port=$port, security=$security, email=[redacted], password=[redacted])"
    fun error(): String? = when {
        !host.matches(Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")) -> "SMTP 地址格式不正确"
        port !in 1..65535 -> "端口必须在 1 到 65535 之间"
        security !in listOf("SSL", "STARTTLS") -> "必须使用 SSL/TLS 或 STARTTLS"
        runCatching { Addresses.parse(email).size != 1 || email.contains(Regex("[,;\\s]")) }.getOrDefault(true) -> "请填写有效的发件邮箱"
        password.isBlank() -> "请填写 SMTP 授权码"
        else -> null
    }
}
enum class DeliveryState { PENDING, SENDING, SENT, FAILED, CONFIG_ERROR, UNCERTAIN }
enum class FailureKind { TRANSIENT, CONFIG, PERMANENT, UNCERTAIN }
object DeliveryPolicy {
    fun afterFailure(kind: FailureKind, attempts: Int): DeliveryState = when (kind) {
        FailureKind.CONFIG -> DeliveryState.CONFIG_ERROR
        FailureKind.PERMANENT -> DeliveryState.FAILED
        FailureKind.UNCERTAIN -> DeliveryState.UNCERTAIN
        FailureKind.TRANSIENT -> if (attempts < 5) DeliveryState.PENDING else DeliveryState.FAILED
    }
}
object Fingerprints {
    fun sms(source: String, timestamp: Long, sim: String, body: String): String {
        val canonical = listOf(source, timestamp.toString(), sim, body).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
