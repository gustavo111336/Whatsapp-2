package com.example.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

enum class EmailVerificationTarget {
    PRIMARY,
    RECOVERY
}

data class EmailVerificationChallenge(
    val targetEmail: String,
    val target: EmailVerificationTarget,
    val code: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val attemptsRemaining: Int = MAX_ATTEMPTS
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAtMs

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

sealed interface CodeVerificationOutcome {
    data object Verified : CodeVerificationOutcome
    data object Expired : CodeVerificationOutcome
    data class Mismatch(val attemptsRemaining: Int) : CodeVerificationOutcome
    data object LockedOut : CodeVerificationOutcome
    data object InvalidFormat : CodeVerificationOutcome
}

/**
 * Generates and validates 6-character alphanumeric (letters + numbers) verification codes
 * for account email confirmation and recovery email confirmation.
 */
object EmailVerificationHelper {
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val ALPHANUMERIC = LETTERS + DIGITS
    private const val CODE_LENGTH = 6
    const val CHALLENGE_TTL_MS = 15 * 60 * 1000L

    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val CODE_REGEX = Regex("^[A-Z0-9]{6}$")
    private val random = SecureRandom()

    /**
     * Generates a 6-character alphanumeric code guaranteed to contain both letters and numbers
     * (e.g., `K7M9P2`).
     */
    fun generateAlphanumericCode(): String {
        val chars = CharArray(CODE_LENGTH)
        // Guarantee at least 2 letters and 2 digits, then fill remaining positions from combined pool
        chars[0] = LETTERS[random.nextInt(LETTERS.length)]
        chars[1] = LETTERS[random.nextInt(LETTERS.length)]
        chars[2] = DIGITS[random.nextInt(DIGITS.length)]
        chars[3] = DIGITS[random.nextInt(DIGITS.length)]
        for (i in 4 until CODE_LENGTH) {
            chars[i] = ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]
        }
        // Fisher-Yates shuffle with SecureRandom
        for (i in CODE_LENGTH - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    fun createChallenge(
        targetEmail: String,
        target: EmailVerificationTarget,
        nowMs: Long = System.currentTimeMillis()
    ): EmailVerificationChallenge = EmailVerificationChallenge(
        targetEmail = targetEmail.trim().lowercase(Locale.ROOT),
        target = target,
        code = generateAlphanumericCode(),
        createdAtMs = nowMs,
        expiresAtMs = nowMs + CHALLENGE_TTL_MS,
        attemptsRemaining = EmailVerificationChallenge.MAX_ATTEMPTS
    )

    fun normalizeCodeInput(raw: String): String =
        raw.uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .take(CODE_LENGTH)

    fun isValidCodeFormat(raw: String): Boolean =
        CODE_REGEX.matches(normalizeCodeInput(raw))

    fun verifyChallenge(
        challenge: EmailVerificationChallenge,
        userInput: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<CodeVerificationOutcome, EmailVerificationChallenge> {
        if (challenge.attemptsRemaining <= 0) {
            return CodeVerificationOutcome.LockedOut to challenge
        }
        if (challenge.isExpired(nowMs)) {
            return CodeVerificationOutcome.Expired to challenge
        }
        val normalized = normalizeCodeInput(userInput)
        if (!CODE_REGEX.matches(normalized)) {
            return CodeVerificationOutcome.InvalidFormat to challenge
        }
        val expectedHash = sha256Bytes(challenge.code)
        val actualHash = sha256Bytes(normalized)
        return if (MessageDigest.isEqual(expectedHash, actualHash)) {
            CodeVerificationOutcome.Verified to challenge
        } else {
            val remaining = (challenge.attemptsRemaining - 1).coerceAtLeast(0)
            val updated = challenge.copy(attemptsRemaining = remaining)
            val outcome = if (remaining == 0) {
                CodeVerificationOutcome.LockedOut
            } else {
                CodeVerificationOutcome.Mismatch(remaining)
            }
            outcome to updated
        }
    }

    fun isValidEmail(raw: String): Boolean {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        return trimmed.length in 5..160 && !trimmed.contains('/') && EMAIL_REGEX.matches(trimmed)
    }

    fun maskEmail(raw: String): String {
        val clean = raw.trim()
        val at = clean.indexOf('@')
        if (at <= 1 || at >= clean.lastIndex) return clean
        val local = clean.substring(0, at)
        val domain = clean.substring(at + 1)
        val maskedLocal = if (local.length <= 2) {
            "${local.first()}*"
        } else {
            "${local.first()}***${local.last()}"
        }
        return "$maskedLocal@$domain"
    }

    private fun sha256Bytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
