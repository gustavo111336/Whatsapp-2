package com.example

import com.example.data.local.AI_PROVIDER_CHATGPT
import com.example.data.local.AI_PROVIDER_CLAUDE
import com.example.data.local.AI_PROVIDER_GEMINI
import com.example.data.local.AI_PROVIDER_GROK
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.UserSettings
import com.example.data.model.ChatMessage
import com.example.service.AiAssistantService
import com.example.service.ChatAiCommand
import com.example.service.CodeVerificationOutcome
import com.example.service.EmailDeliveryService
import com.example.service.EmailVerificationHelper
import com.example.service.EmailVerificationTarget
import com.example.service.ScheduledMessageTimeUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun parseSlashModelCommand_handlesStandardAndFormattedNames() {
    assertEquals("gemini-3.8-flash", AiAssistantService.parseSlashModelCommand("/model gemini-3.8-flash"))
    assertEquals("gpt-4o-mini", AiAssistantService.parseSlashModelCommand("/model **gpt-4o-mini**"))
    assertEquals("llama-3.3-70b-versatile", AiAssistantService.parseSlashModelCommand("  /MODEL   `llama-3.3-70b-versatile`  "))
    assertEquals("", AiAssistantService.parseSlashModelCommand("/model"))
    assertNull(AiAssistantService.parseSlashModelCommand("olá tudo bem?"))
  }

  @Test
  fun normalizeModelName_fallsBackToDefaultWhenBlank() {
    assertEquals(DEFAULT_AI_MODEL, AiAssistantService.normalizeModelName("   "))
    assertEquals("gemini-4-argon", AiAssistantService.normalizeModelName("**gemini-4-argon**"))
  }

  @Test
  fun parseChatAiCommand_recognizesEnableConfigureDisableAndPrompt() {
    assertEquals(ChatAiCommand.EnableAndSpeak(null), AiAssistantService.parseChatAiCommand("/ai"))
    assertEquals(ChatAiCommand.EnableAndSpeak(null), AiAssistantService.parseChatAiCommand("  /AI  "))
    assertEquals(ChatAiCommand.Configure, AiAssistantService.parseChatAiCommand("/ai --configure"))
    assertEquals(ChatAiCommand.Configure, AiAssistantService.parseChatAiCommand("/ai -c"))
    assertEquals(ChatAiCommand.Configure, AiAssistantService.parseChatAiCommand("/ai config"))
    assertEquals(ChatAiCommand.Disable, AiAssistantService.parseChatAiCommand("/ai off"))
    assertEquals(ChatAiCommand.Disable, AiAssistantService.parseChatAiCommand("/ai sair"))
    assertEquals(
      ChatAiCommand.EnableAndSpeak("resume o chat aí"),
      AiAssistantService.parseChatAiCommand("/ai resume o chat aí")
    )
    assertNull(AiAssistantService.parseChatAiCommand("oi /ai tudo bem"))
    assertNull(AiAssistantService.parseChatAiCommand("/aide"))
  }

  @Test
  fun resolvePersonaNameAndPresets_mapProvidersAndAutoDetection() {
    assertEquals("Grok", AiAssistantService.presetFor(AI_PROVIDER_GROK).defaultPersona)
    assertEquals("ChatGPT", AiAssistantService.presetFor(AI_PROVIDER_CHATGPT).defaultPersona)
    assertEquals("Claude", AiAssistantService.presetFor(AI_PROVIDER_CLAUDE).defaultPersona)
    assertEquals("Gemini", AiAssistantService.presetFor(AI_PROVIDER_GEMINI).defaultPersona)

    assertEquals(
      "Grok",
      AiAssistantService.resolvePersonaName(UserSettings(aiProvider = AI_PROVIDER_GROK))
    )
    assertEquals(
      "ChatGPT",
      AiAssistantService.resolvePersonaName(UserSettings(aiProvider = AI_PROVIDER_CHATGPT))
    )
    assertEquals(
      "MeuBot",
      AiAssistantService.resolvePersonaName(
        UserSettings(aiProvider = AI_PROVIDER_GROK, aiPersonaName = "  MeuBot  ")
      )
    )
    assertEquals(
      "Claude",
      AiAssistantService.resolvePersonaName(
        UserSettings(aiApiKey = "sk-ant-abc", aiModel = "claude-sonnet-4-20250514")
      )
    )
  }

  @Test
  fun isValidExternalHttpsUrl_blocksInsecureAndInternalHosts() {
    assertTrue(AiAssistantService.isValidExternalHttpsUrl(""))
    assertTrue(AiAssistantService.isValidExternalHttpsUrl("https://api.x.ai/v1/chat/completions"))
    assertTrue(AiAssistantService.isValidExternalHttpsUrl("https://api.openai.com/v1/chat/completions"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("http://api.openai.com/v1/chat/completions"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("https://localhost:8080/v1"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("https://127.0.0.1/v1"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("https://192.168.1.10/v1"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("https://10.0.0.5/v1"))
    assertFalse(AiAssistantService.isValidExternalHttpsUrl("https://172.16.0.1/v1"))
  }

  @Test
  fun formatRoomTranscript_includesParticipantsAndRedactsViewOnceContent() {
    val messages = listOf(
      ChatMessage(
        id = "m1",
        senderId = "u1",
        senderName = "Lucas",
        text = "Bora marcar o rolê?"
      ),
      ChatMessage(
        id = "m2",
        senderId = "u2",
        senderName = "Marina",
        text = "Segredo super secreto",
        viewOnce = true,
        messageType = "TEXT"
      ),
      ChatMessage(
        id = "m3",
        senderId = "u1",
        senderName = "Lucas",
        text = "Boa ideia!",
        aiGenerated = true,
        aiPersona = "Grok"
      ),
      ChatMessage(
        id = "m4",
        senderId = "u2",
        senderName = "Marina",
        messageType = "GIF_AUDIO"
      )
    )

    val transcript = AiAssistantService.formatRoomTranscript(
      chatTitle = "Grupo Geral",
      recentMessages = messages,
      currentUserId = "u1",
      currentUserName = "Eu"
    )

    assertTrue(transcript.contains("Eu: Bora marcar o rolê?"))
    assertTrue(transcript.contains("Marina: [Enviou mensagem de texto de visualização única (conteúdo privado)]"))
    assertFalse(transcript.contains("Segredo super secreto"))
    assertTrue(transcript.contains("IA (Grok): Boa ideia!"))
    assertTrue(transcript.contains("Marina: [Enviou um GIF com áudio]"))
  }

  @Test
  fun generateAlphanumericCode_isSixCharsWithLettersAndDigits() {
    repeat(25) {
      val code = EmailVerificationHelper.generateAlphanumericCode()
      assertEquals(6, code.length)
      assertTrue("Code $code should contain uppercase letters", code.any { it in 'A'..'Z' })
      assertTrue("Code $code should contain digits", code.any { it in '0'..'9' })
      assertTrue("Code $code must match [A-Z0-9]{6}", EmailVerificationHelper.isValidCodeFormat(code))
    }
  }

  @Test
  fun verifyChallenge_handlesCaseInsensitiveMatchAndMismatchAndExpiry() {
    val now = 1_000_000L
    val challenge = EmailVerificationHelper.createChallenge(
      targetEmail = "user@example.com",
      target = EmailVerificationTarget.PRIMARY,
      nowMs = now
    )

    val (badOutcome, afterBad) = EmailVerificationHelper.verifyChallenge(challenge, "WRONG1", nowMs = now + 1000L)
    assertTrue(badOutcome is CodeVerificationOutcome.Mismatch)
    assertEquals(4, (badOutcome as CodeVerificationOutcome.Mismatch).attemptsRemaining)

    val (okOutcome, _) = EmailVerificationHelper.verifyChallenge(
      afterBad,
      challenge.code.lowercase(),
      nowMs = now + 2000L
    )
    assertEquals(CodeVerificationOutcome.Verified, okOutcome)

    val (expiredOutcome, _) = EmailVerificationHelper.verifyChallenge(
      challenge,
      challenge.code,
      nowMs = challenge.expiresAtMs + 1L
    )
    assertEquals(CodeVerificationOutcome.Expired, expiredOutcome)
  }

  @Test
  fun emailValidationAndMasking_workCorrectly() {
    assertTrue(EmailVerificationHelper.isValidEmail("backup.user@gmail.com"))
    assertFalse(EmailVerificationHelper.isValidEmail("invalid/path@gmail.com"))
    assertFalse(EmailVerificationHelper.isValidEmail("not-an-email"))
    assertEquals("a***a@gmail.com", EmailVerificationHelper.maskEmail("alice.silva@gmail.com"))
  }

  @Test
  fun emailDeliverySecurity_stripsCrlfAndRequiresHttps() {
    assertEquals("user@gmail.comBcc:evil", EmailDeliveryService.sanitizeHeaderValue(" user@gmail.com\r\nBcc:evil\n "))
    assertTrue(EmailDeliveryService.isSecureHttpsUrl("https://script.google.com/macros/s/abc/exec"))
    assertFalse(EmailDeliveryService.isSecureHttpsUrl("http://script.google.com/macros/s/abc/exec"))
    assertFalse(EmailDeliveryService.isSecureHttpsUrl("javascript:alert(1)"))
  }

  @Test
  fun scheduledMessageTimeUtils_combinesUtcDateAndLocalTimeWithoutDayShift() {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(2027, Calendar.JULY, 6, 0, 0, 0)
    }
    val saoPauloTz = TimeZone.getTimeZone("America/Sao_Paulo")
    val combinedMs = ScheduledMessageTimeUtils.combineDateAndTimeMillis(
      utcDatePickerMillis = utcCal.timeInMillis,
      hour = 12,
      minute = 0,
      targetTimeZone = saoPauloTz
    )
    val formatted = ScheduledMessageTimeUtils.formatScheduledDateTime(combinedMs, saoPauloTz)
    assertEquals("06/07/2027 às 12:00", formatted)

    assertTrue(ScheduledMessageTimeUtils.isValidFutureSchedule(combinedMs, nowMs = combinedMs - 15_000L))
    assertFalse(ScheduledMessageTimeUtils.isValidFutureSchedule(combinedMs, nowMs = combinedMs - 5_000L))
  }

  @Test
  fun chatMessage_defaultsSupportViewOnceAndGifAudio() {
    val msg = ChatMessage(
      id = "m1",
      messageType = "GIF_AUDIO",
      viewOnce = true,
      audioUrl = "https://example.com/a.m4a"
    )
    assertTrue(msg.viewOnce)
    assertTrue(msg.viewedBy.isEmpty())
    assertEquals("GIF_AUDIO", msg.messageType)
    assertEquals("https://example.com/a.m4a", msg.audioUrl)
  }
}
