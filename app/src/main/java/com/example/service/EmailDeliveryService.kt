package com.example.service

import android.util.Base64
import com.example.BuildConfig
import com.example.data.local.EMAIL_DELIVERY_APPS_SCRIPT
import com.example.data.local.EMAIL_DELIVERY_AUTO
import com.example.data.local.EMAIL_DELIVERY_GMAIL_SMTP
import com.example.data.local.EMAIL_DELIVERY_RESEND
import com.example.data.local.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed interface EmailCodeDispatchResult {
    data class SentViaGmailSmtp(val maskedRecipient: String) : EmailCodeDispatchResult
    data class SentViaWebhook(val maskedRecipient: String) : EmailCodeDispatchResult
    data class SentViaResend(val maskedRecipient: String) : EmailCodeDispatchResult
    data class NoCustomMailerConfigured(val reason: String) : EmailCodeDispatchResult
    data class Failed(val message: String) : EmailCodeDispatchResult
}

/**
 * Delivers 6-character alphanumeric verification codes directly to Gmail via:
 * 1. Gmail SMTP over SSL (`smtp.gmail.com:465`) using a sender Gmail + 16-char App Password,
 * 2. HTTPS Webhook (e.g., Google Apps Script Web App), or
 * 3. Resend HTTPS API.
 *
 * Security notes:
 * - Never prints the verification code or credentials to logs or screen.
 * - Strips CR/LF characters before writing SMTP headers to prevent header injection.
 * - Requires HTTPS for Webhook/API calls.
 */
object EmailDeliveryService {

    private const val TIMEOUT_MS = 15_000
    private const val SMTP_HOST = "smtp.gmail.com"
    private const val SMTP_PORT = 465

    fun sanitizeHeaderValue(raw: String): String =
        raw.replace("\r", "").replace("\n", "").trim()

    fun isSecureHttpsUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val url = URL(trimmed)
            url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun hasConfiguredCustomMailer(settings: UserSettings): Boolean {
        val smtpUser = settings.emailSenderAddress.ifBlank { BuildConfig.EMAIL_SMTP_USER }.trim()
        val smtpPass = settings.emailSmtpAppPassword.ifBlank { BuildConfig.EMAIL_SMTP_APP_PASSWORD }.trim()
        val webhook = settings.emailWebhookUrl.ifBlank { BuildConfig.EMAIL_WEBHOOK_URL }.trim()
        val resendKey = settings.emailResendApiKey.ifBlank { BuildConfig.EMAIL_RESEND_API_KEY }.trim()

        return (EmailVerificationHelper.isValidEmail(smtpUser) && smtpPass.isNotBlank()) ||
            isSecureHttpsUrl(webhook) ||
            resendKey.isNotBlank()
    }

    suspend fun sendVerificationCodeEmail(
        recipientEmail: String,
        code: String,
        isRecovery: Boolean,
        settings: UserSettings
    ): EmailCodeDispatchResult = withContext(Dispatchers.IO) {
        val cleanRecipient = sanitizeHeaderValue(recipientEmail).lowercase()
        if (!EmailVerificationHelper.isValidEmail(cleanRecipient)) {
            return@withContext EmailCodeDispatchResult.Failed("E-mail de destino inválido.")
        }

        val masked = EmailVerificationHelper.maskEmail(cleanRecipient)
        val subject = if (isRecovery) {
            "WhatwillIdo - Código de recuperação de e-mail"
        } else {
            "WhatwillIdo - Código de verificação de e-mail"
        }
        val purpose = if (isRecovery) {
            "confirmar seu e-mail de recuperação no WhatwillIdo"
        } else {
            "verificar seu e-mail ao entrar no WhatwillIdo"
        }
        val textBody = buildString {
            appendLine("Olá!")
            appendLine()
            appendLine("Use o código de 6 caracteres (letras e números) abaixo para $purpose:")
            appendLine()
            appendLine("Código: $code")
            appendLine()
            appendLine("Este código expira em 15 minutos.")
            appendLine("Se você não solicitou este código, ignore esta mensagem.")
        }
        val htmlBody = """
            <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;border:1px solid #e0e0e0;border-radius:16px;">
              <h2 style="margin-top:0;color:#1b5e20;">WhatwillIdo</h2>
              <p style="color:#333;">Use o código alfanumérico de 6 caracteres abaixo para <strong>$purpose</strong>:</p>
              <div style="background:#f1f8e9;border-radius:12px;padding:16px;text-align:center;font-family:monospace;font-size:28px;font-weight:bold;letter-spacing:6px;color:#1b5e20;">
                $code
              </div>
              <p style="color:#666;font-size:13px;margin-top:16px;">Válido por 15 minutos. Nunca compartilhe este código.</p>
            </div>
        """.trimIndent()

        val smtpUser = settings.emailSenderAddress.ifBlank { BuildConfig.EMAIL_SMTP_USER }.trim()
        val smtpPass = settings.emailSmtpAppPassword.ifBlank { BuildConfig.EMAIL_SMTP_APP_PASSWORD }
            .replace(" ", "").trim()
        val webhookUrl = settings.emailWebhookUrl.ifBlank { BuildConfig.EMAIL_WEBHOOK_URL }.trim()
        val resendKey = settings.emailResendApiKey.ifBlank { BuildConfig.EMAIL_RESEND_API_KEY }.trim()
        val resendFrom = settings.emailResendFrom.ifBlank { "WhatwillIdo <onboarding@resend.dev>" }.trim()

        val mode = settings.emailDeliveryMode

        // 1. Gmail SMTP if selected or available in AUTO
        if ((mode == EMAIL_DELIVERY_GMAIL_SMTP || mode == EMAIL_DELIVERY_AUTO) &&
            EmailVerificationHelper.isValidEmail(smtpUser) && smtpPass.isNotBlank()
        ) {
            return@withContext runCatching {
                sendViaGmailSmtpSsl(
                    smtpUser = sanitizeHeaderValue(smtpUser),
                    smtpAppPassword = smtpPass,
                    toEmail = cleanRecipient,
                    subject = subject,
                    textBody = textBody
                )
                EmailCodeDispatchResult.SentViaGmailSmtp(masked)
            }.getOrElse { err ->
                EmailCodeDispatchResult.Failed(
                    "Falha ao enviar via Gmail SMTP: ${err.localizedMessage ?: "verifique o e-mail e a Senha de App."}"
                )
            }
        }

        // 2. HTTPS Webhook / Google Apps Script if selected or available in AUTO
        if ((mode == EMAIL_DELIVERY_APPS_SCRIPT || mode == EMAIL_DELIVERY_AUTO) &&
            isSecureHttpsUrl(webhookUrl)
        ) {
            return@withContext runCatching {
                sendViaHttpsWebhook(
                    webhookUrl = webhookUrl,
                    toEmail = cleanRecipient,
                    subject = subject,
                    textBody = textBody,
                    htmlBody = htmlBody,
                    code = code
                )
                EmailCodeDispatchResult.SentViaWebhook(masked)
            }.getOrElse { err ->
                EmailCodeDispatchResult.Failed(
                    "Falha ao enviar via Webhook HTTPS: ${err.localizedMessage ?: "erro de conexão."}"
                )
            }
        }

        // 3. Resend API if selected or available in AUTO
        if ((mode == EMAIL_DELIVERY_RESEND || mode == EMAIL_DELIVERY_AUTO) && resendKey.isNotBlank()) {
            return@withContext runCatching {
                sendViaResendApi(
                    apiKey = resendKey,
                    fromAddress = sanitizeHeaderValue(resendFrom),
                    toEmail = cleanRecipient,
                    subject = subject,
                    textBody = textBody,
                    htmlBody = htmlBody
                )
                EmailCodeDispatchResult.SentViaResend(masked)
            }.getOrElse { err ->
                EmailCodeDispatchResult.Failed(
                    "Falha ao enviar via Resend: ${err.localizedMessage ?: "verifique a API Key."}"
                )
            }
        }

        EmailCodeDispatchResult.NoCustomMailerConfigured(
            "Nenhum servidor de envio (Gmail SMTP, Apps Script ou Resend) está configurado."
        )
    }

    private fun sendViaGmailSmtpSsl(
        smtpUser: String,
        smtpAppPassword: String,
        toEmail: String,
        subject: String,
        textBody: String
    ) {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = (factory.createSocket(SMTP_HOST, SMTP_PORT) as SSLSocket).apply {
            soTimeout = TIMEOUT_MS
            startHandshake()
        }
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(s.outputStream, StandardCharsets.UTF_8))

            fun expectCode(vararg expectedPrefixes: Int) {
                var lastLine: String?
                do {
                    lastLine = reader.readLine()
                        ?: throw IllegalStateException("Conexão SMTP encerrada pelo servidor.")
                    val code = lastLine.take(3).toIntOrNull()
                    if (lastLine.length >= 4 && lastLine[3] == '-') {
                        continue
                    }
                    if (code == null || code !in expectedPrefixes) {
                        throw IllegalStateException("Resposta SMTP inesperada ($code).")
                    }
                    break
                } while (true)
            }

            fun sendLine(cmd: String) {
                writer.write(cmd)
                writer.write("\r\n")
                writer.flush()
            }

            expectCode(220)
            sendLine("EHLO whatwillido.app")
            expectCode(250)
            sendLine("AUTH LOGIN")
            expectCode(334)
            sendLine(Base64.encodeToString(smtpUser.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
            expectCode(334)
            sendLine(Base64.encodeToString(smtpAppPassword.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
            expectCode(235)
            sendLine("MAIL FROM:<$smtpUser>")
            expectCode(250)
            sendLine("RCPT TO:<$toEmail>")
            expectCode(250, 251)
            sendLine("DATA")
            expectCode(354)

            val encodedSubject = "=?UTF-8?B?" +
                Base64.encodeToString(subject.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) + "?="
            val dotStuffedBody = textBody
                .replace("\r\n", "\n")
                .split("\n")
                .joinToString("\r\n") { line -> if (line.startsWith(".")) ".$line" else line }

            writer.write("From: WhatwillIdo <$smtpUser>\r\n")
            writer.write("To: <$toEmail>\r\n")
            writer.write("Subject: $encodedSubject\r\n")
            writer.write("MIME-Version: 1.0\r\n")
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n")
            writer.write("\r\n")
            writer.write(dotStuffedBody)
            writer.write("\r\n.\r\n")
            writer.flush()

            expectCode(250)
            sendLine("QUIT")
        }
    }

    private fun sendViaHttpsWebhook(
        webhookUrl: String,
        toEmail: String,
        subject: String,
        textBody: String,
        htmlBody: String,
        code: String
    ) {
        val conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            val json = JSONObject().apply {
                put("to", toEmail)
                put("subject", subject)
                put("text", textBody)
                put("html", htmlBody)
                put("code", code)
            }.toString()
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..302) {
                throw IllegalStateException("HTTP $status ao chamar webhook.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sendViaResendApi(
        apiKey: String,
        fromAddress: String,
        toEmail: String,
        subject: String,
        textBody: String,
        htmlBody: String
    ) {
        val conn = (URL("https://api.resend.com/emails").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        try {
            val json = JSONObject().apply {
                put("from", fromAddress)
                put("to", org.json.JSONArray(listOf(toEmail)))
                put("subject", subject)
                put("text", textBody)
                put("html", htmlBody)
            }.toString()
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(180)
                throw IllegalStateException("HTTP $status ($errBody)")
            }
        } finally {
            conn.disconnect()
        }
    }

    const val GOOGLE_APPS_SCRIPT_SNIPPET = """function doPost(e) {
  var d = JSON.parse(e.postData.contents);
  MailApp.sendEmail({
    to: d.to,
    subject: d.subject,
    body: d.text,
    htmlBody: d.html
  });
  return ContentService.createTextOutput("OK");
}"""
}
