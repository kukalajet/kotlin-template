package kotlintemplate.config

/** A secret whose `toString` prints `<redacted>`, so logging or printing the holder cannot leak it. */
class Redacted<out T>(
    private val value: T,
) {
    fun expose(): T = value

    override fun toString(): String = "<redacted>"

    override fun equals(other: Any?): Boolean = other is Redacted<*> && other.value == value

    override fun hashCode(): Int = value.hashCode()
}
