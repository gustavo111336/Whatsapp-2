package com.example

import com.example.data.local.DEFAULT_AI_MODEL
import com.example.service.AiAssistantService
import com.example.service.CodeVerificationOutcome
import com.example.service.EmailDeliveryService
import com.example.service.EmailVerificationHelper
import com.example.service.EmailVerificationTarget
import org.junit.Assert.*
import org.junit.Test

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
}
