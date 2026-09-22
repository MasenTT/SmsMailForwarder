package cn.smsmail.app

import cn.smsmail.core.*
import com.sun.mail.smtp.SMTPAddressFailedException
import java.util.Date
import java.util.Properties
import javax.mail.*
import javax.net.ssl.SSLContext
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class MailResult(val kind: FailureKind? = null, val detail: String)
interface MailGateway { fun send(config: MailConfig, recipient: String, subject: String, body: String): MailResult }

class SmtpGateway : MailGateway {
    override fun send(config: MailConfig, recipient: String, subject: String, body: String): MailResult {
        config.error()?.let { return MailResult(FailureKind.CONFIG, it) }
        var submitting = false
        var transport: Transport? = null
        return try {
            val properties = Properties().apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.ssl.enable", (config.security == "SSL").toString())
                setProperty("mail.smtp.starttls.enable", (config.security == "STARTTLS").toString())
                setProperty("mail.smtp.starttls.required", (config.security == "STARTTLS").toString())
                setProperty("mail.smtp.ssl.checkserveridentity", "true")
                setProperty("mail.smtp.ssl.protocols", SSLContext.getDefault().supportedSSLParameters.protocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.joinToString(" "))
                setProperty("mail.smtp.connectiontimeout", "15000")
                setProperty("mail.smtp.timeout", "20000")
                setProperty("mail.smtp.writetimeout", "20000")
                setProperty("mail.smtp.sendpartial", "false")
            }
            val session = Session.getInstance(properties).apply { debug = false }
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.email))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient, true))
                setSubject(subject.replace('\r', ' ').replace('\n', ' '), "UTF-8")
                setText(body, "UTF-8"); sentDate = Date(); saveChanges()
            }
            transport = session.getTransport("smtp")
            transport.connect(config.host, config.port, config.email, config.password)
            submitting = true
            transport.sendMessage(message, message.allRecipients)
            MailResult(detail = "已提交邮件服务器；请以实际收件为准")
        } catch (_: AuthenticationFailedException) {
            MailResult(FailureKind.CONFIG, "邮箱认证失败，请检查账号、SMTP 开关及授权码")
        } catch (e: SendFailedException) {
            val addressFailure = e.nextException as? SMTPAddressFailedException
            if (addressFailure != null && addressFailure.returnCode in 400..499)
                MailResult(FailureKind.TRANSIENT, "邮件服务器暂时拒绝收件人，稍后重试")
            else MailResult(FailureKind.PERMANENT, "服务器拒绝收件人或邮件，请核对邮箱及发送限制")
        } catch (_: Exception) {
            if (submitting) MailResult(FailureKind.UNCERTAIN, "提交期间连接中断，邮件可能已送出；核实收件后再手动重试")
            else MailResult(FailureKind.TRANSIENT, "连接失败，请检查网络、SMTP 地址、端口和 TLS 设置")
        } finally {
            try { transport?.close() } catch (_: MessagingException) { /* Submission result is independent of QUIT. */ }
        }
    }
}
