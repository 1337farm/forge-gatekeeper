package com.forgerig.nanogatekeeper.sanitizer

import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.log2

data class ScrubEvent(val type: String, val replacement: String)
data class ScrubResult(val sanitizedText: String, val events: List<ScrubEvent>)

object DeterministicScrubber {

    private val EMAIL: Pattern =
        Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b")
    private val IPV4: Pattern =
        Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b")
    private val CC: Pattern = Pattern.compile("\\b(?:\\d[ \\-]?){13,19}\\b")
    private val AWS_KEY: Pattern = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")
    private val GCP_KEY: Pattern = Pattern.compile("\\bAIza[0-9A-Za-z\\-_]{35}\\b")
    private val GENERIC_SECRET: Pattern =
        Pattern.compile("(?i)\\b(api[_\\-]?key|secret|bearer)\\b\\s*[:=]\\s*['\"]?([^\\s'\"]{8,})['\"]?")
    private val HIGH_ENTROPY_TOKEN: Pattern =
        Pattern.compile("\\b[A-Za-z0-9+/=_\\-]{24,}\\b")

    fun scrub(input: String): ScrubResult {
        val events = mutableListOf<ScrubEvent>()
        var text = input
        text = replaceNonDestructive(text, EMAIL, "[EMAIL_REDACTED]", "email", events)
        text = replaceNonDestructive(text, IPV4, "[IP_REDACTED]", "ipv4", events)
        text = replaceNonDestructive(text, AWS_KEY, "[API_KEY_REDACTED]", "aws_key", events)
        text = replaceNonDestructive(text, GCP_KEY, "[API_KEY_REDACTED]", "gcp_key", events)
        text = replaceNonDestructive(text, CC, "[CARD_REDACTED]", "card", events, ::luhnLike)
        text = replaceNonDestructive(text, GENERIC_SECRET, "\$1=[SECRET_REDACTED]", "secret", events)
        text = scrubHighEntropy(text, events)
        return ScrubResult(text, events)
    }

    private fun replaceNonDestructive(
        input: String,
        pattern: Pattern,
        replacement: String,
        type: String,
        events: MutableList<ScrubEvent>,
        accept: ((String) -> Boolean)? = null
    ): String {
        val m: Matcher = pattern.matcher(input)
        val sb = StringBuffer(input.length + 32)
        var changed = false
        while (m.find()) {
            val hit = m.group()
            if (accept != null && !accept(hit)) continue
            events.add(ScrubEvent(type, replacement))
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement))
            changed = true
        }
        m.appendTail(sb)
        return if (changed) sb.toString() else input
    }

    private fun scrubHighEntropy(input: String, events: MutableList<ScrubEvent>): String {
        val m: Matcher = HIGH_ENTROPY_TOKEN.matcher(input)
        val sb = StringBuffer(input.length + 32)
        var changed = false
        while (m.find()) {
            val tok = m.group()
            if (tok.startsWith("[")) continue
            val entropy = shannonEntropy(tok)
            val looksSecret = tok.contains("=") || tok.contains("/") || tok.contains("+")
            if ((looksSecret && entropy >= 4.2) || entropy >= 4.6) {
                events.add(ScrubEvent("high_entropy", "[TOKEN_REDACTED]"))
                m.appendReplacement(sb, Matcher.quoteReplacement("[TOKEN_REDACTED]"))
                changed = true
            }
        }
        m.appendTail(sb)
        return if (changed) sb.toString() else input
    }

    fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (c in s.toCharArray()) freq[c.code and 0xFF]++
        var e = 0.0
        for (f in freq) {
            if (f == 0) continue
            val p = f.toDouble() / s.length
            e -= p * (log2(p))
        }
        return e
    }

    internal fun luhnLike(digits: String): Boolean {
        val d = digits.filter { it.isDigit() }
        if (d.length < 13 || d.length > 19) return false
        var sum = 0
        var alt = false
        for (i in d.length - 1 downTo 0) {
            var n = d[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
