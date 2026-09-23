package cn.smsmail.app

import cn.smsmail.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Properties
import javax.net.ssl.*
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.concurrent.thread

/** Disposable local certificate and credentials. Never connects to an external mail service. */
class SmtpGatewayTest {
    private lateinit var original: SSLContext
    private lateinit var trusted: SSLContext
    @Before fun tlsContext() {
        original = SSLContext.getDefault()
        val store = KeyStore.getInstance("PKCS12").apply {
            SmtpGatewayTest::class.java.getResourceAsStream("/smtp-test-only.p12").use { load(it, "test-only".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        trusted = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        SSLContext.setDefault(trusted)
    }
    @After fun restore() { SSLContext.setDefault(original) }
    private fun send(mode: String, security: String = "SSL", attachments: List<MailAttachment> = emptyList(), captured: StringBuilder? = null): MailResult {
        val server = if (security == "SSL") trusted.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) else ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server.soTimeout = 5000
        val serving = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    var reader = socket.getInputStream().bufferedReader()
                    var writer = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { writer.write(text + "\r\n"); writer.flush() }
                    reply("220 localhost test SMTP")
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("EHLO") -> reply(if (security == "STARTTLS" && mode != "no-starttls") "250-localhost\r\n250-STARTTLS\r\n250 AUTH PLAIN LOGIN" else "250-localhost\r\n250 AUTH PLAIN LOGIN")
                            line == "STARTTLS" -> {
                                reply("220 Ready for TLS")
                                val tls = trusted.socketFactory.createSocket(socket, "localhost", server.localPort, true) as SSLSocket
                                tls.useClientMode = false
                                tls.startHandshake()
                                reader = tls.getInputStream().bufferedReader()
                                writer = tls.getOutputStream().bufferedWriter()
                            }
                            line.startsWith("AUTH") -> reply(if (mode == "auth") "535 5.7.8 Invalid credentials" else "235 2.7.0 Accepted")
                            line.startsWith("MAIL FROM") -> reply("250 OK")
                            line.startsWith("RCPT TO") -> reply(if (mode == "recipient") "550 5.1.1 Unknown user" else "250 OK")
                            line == "DATA" -> {
                                reply("354 End with dot")
                                val messageLines = mutableListOf<String>()
                                while (true) { val body = reader.readLine() ?: break; if (body == ".") break; messageLines += body }
                                captured?.append(messageLines.joinToString("\n"))
                                if (mode == "disconnect") break else reply("250 Queued")
                            }
                            line == "QUIT" -> { reply("221 Bye"); break }
                            else -> reply("250 OK")
                        }
                    }
                }
            } catch (_: Exception) { /* Rejected TLS is an expected test scenario. */ }
        }
        return try {
            SmtpGateway().send(MailConfig("localhost", server.localPort, security, "sender@example.com", "test-only"), "recipient@example.com", "测试通知", "中文\n多行短信", attachments)
        } finally { server.close(); serving.join(6000) }
    }
    @Test fun startTlsSubmissionAccepted() { assertNull(send("success", "STARTTLS").kind) }
    @Test fun startTlsCannotDowngradeToPlaintext() { assertNotNull(send("no-starttls", "STARTTLS").kind) }
    @Test fun tlsSubmissionAccepted() { assertNull(send("success").kind) }
    @Test fun authenticationFailurePauses() { assertEquals(FailureKind.CONFIG, send("auth").kind) }
    @Test fun rejectedRecipientIsPermanent() { assertEquals(FailureKind.PERMANENT, send("recipient").kind) }
    @Test fun disconnectAfterDataIsUncertain() { assertEquals(FailureKind.UNCERTAIN, send("disconnect").kind) }
    @Test fun untrustedCertificateNeverSubmits() {
        SSLContext.setDefault(original)
        assertNotNull(send("success").kind)
    }
    @Test fun missingAuthorizationRejectedBeforeNetwork() {
        val result = SmtpGateway().send(MailConfig("localhost", 465, "SSL", "sender@example.com", ""), "recipient@example.com", "test", "test")
        assertEquals(FailureKind.CONFIG, result.kind)
    }
    @Test fun sendsMmsAsMultipartWithNamedBinaryAttachment() {
        val captured = StringBuilder()
        val result = send("success", attachments = listOf(MailAttachment("photo.png", "image/png", byteArrayOf(1, 2, 3))), captured = captured)
        assertNull(result.kind)
        assertTrue(captured.contains("multipart/mixed"))
        val message = MimeMessage(Session.getInstance(Properties()), captured.toString().byteInputStream())
        val parts = message.content as MimeMultipart
        assertTrue(parts.getBodyPart(0).content.toString().contains("多行短信"))
        val attachment = parts.getBodyPart(1)
        assertEquals("photo.png", attachment.fileName)
        assertEquals("image/png", attachment.contentType.substringBefore(';'))
        assertArrayEquals(byteArrayOf(1, 2, 3), attachment.inputStream.readBytes())
    }
    @Test fun attachmentFileNameCannotInjectMimeHeaders() {
        val captured = StringBuilder()
        val result = send("success", attachments = listOf(MailAttachment("photo.png\r\nX-Evil: yes", "image/png", byteArrayOf(1))), captured = captured)
        assertNull(result.kind)
        val message = MimeMessage(Session.getInstance(Properties()), captured.toString().byteInputStream())
        val attachment = (message.content as MimeMultipart).getBodyPart(1)
        assertNull(attachment.getHeader("X-Evil"))
        assertTrue(attachment.fileName.startsWith("photo.png_"))
        assertTrue(attachment.contentType.startsWith("image/png"))
    }
}
