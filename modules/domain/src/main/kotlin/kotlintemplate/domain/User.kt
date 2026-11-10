package kotlintemplate.domain

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.time.Instant
import java.util.UUID

/** 320 = a 64-character local part, "@", and a 255-character domain (RFC 5321). */
internal const val EMAIL_MAX_CHARS = 320
internal const val DISPLAY_NAME_MAX_CHARS = 100

@JvmInline
value class UserId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

data object InvalidEmail

/**
 * A pragmatic shape check: one "@", no whitespace or control characters, and
 * a dot inside the domain. Deliverability is the mail provider's concern. The
 * constructor is private, so [parse] is the only way to build one.
 */
@JvmInline
value class Email private constructor(
    val value: String,
) {
    companion object {
        fun parse(raw: String): Either<InvalidEmail, Email> =
            either {
                val email = raw.trim()

                ensure(email.codePointLength() <= EMAIL_MAX_CHARS) { InvalidEmail }
                ensure(email.codePoints().noneMatch(::isForbidden)) { InvalidEmail }

                val local = email.substringBefore('@', missingDelimiterValue = "")
                val domain = email.substringAfter('@', missingDelimiterValue = "")

                ensure(local.isNotEmpty() && '@' !in domain && hasInnerDot(domain)) { InvalidEmail }

                Email(email)
            }

        private fun isForbidden(codePoint: Int): Boolean =
            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint)

        /** A dot with at least one character on each side. */
        private fun hasInnerDot(domain: String): Boolean =
            domain.indexOf('.', startIndex = 1) in 1 until domain.length - 1
    }
}

data object InvalidDisplayName

/**
 * 1 to 100 characters after trimming. Control characters have no place in a
 * name, and PostgreSQL text cannot store NUL at all.
 */
@JvmInline
value class DisplayName private constructor(
    val value: String,
) {
    companion object {
        fun parse(raw: String): Either<InvalidDisplayName, DisplayName> =
            either {
                val name = raw.trim()

                ensure(name.codePointLength() in 1..DISPLAY_NAME_MAX_CHARS) { InvalidDisplayName }
                ensure(name.codePoints().noneMatch(Character::isISOControl)) { InvalidDisplayName }

                DisplayName(name)
            }
    }
}

data class CreateUser(
    val email: Email,
    val displayName: DisplayName,
)

data class User(
    val id: UserId,
    val email: Email,
    val displayName: DisplayName,
    val createdAt: Instant,
)

data class UserNotFound(
    val id: UserId,
)

data class UserAlreadyExists(
    val email: Email,
)

/** Characters, not UTF-16 units: "é" is one, and so is an emoji. */
private fun String.codePointLength(): Int = codePointCount(0, length)
