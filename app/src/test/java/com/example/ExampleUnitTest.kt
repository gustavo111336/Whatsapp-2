package com.example

import com.example.data.local.DEFAULT_AI_MODEL
import com.example.service.AiAssistantService
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
}
