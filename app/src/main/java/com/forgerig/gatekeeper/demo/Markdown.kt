package com.forgerig.gatekeeper.demo

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

// Minimal markdown painter for model output: fenced code blocks, inline
// code, bold, headers and list bullets — rendered as spans on plain text.
// No WebView, no HTML round-trip (which shreds <, >, & into entities).
// Unclosed fences (truncated streams) render the tail as code.
object Markdown {

    fun spannify(text: String, codeBg: Int): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val codeRanges = ArrayList<IntRange>()
        val lines = text.lines()
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            val fence = Regex("^\\s*```(\\w*)\\s*$").matchEntire(line)
            if (fence != null) {
                val lang = fence.groupValues[1]
                val code = StringBuilder()
                idx++
                while (idx < lines.size && !lines[idx].trimStart().startsWith("```")) {
                    code.append(lines[idx]).append('\n')
                    idx++
                }
                // Skip the closing fence when present; an unclosed fence
                // simply renders to the end as code.
                if (idx < lines.size) idx++
                val body = code.toString().trimEnd('\n')
                if (body.isNotEmpty() || lang.isNotEmpty()) {
                    if (out.isNotEmpty()) out.append("\n\n")
                    val head = if (lang.isNotEmpty()) "$lang\n" else ""
                    val start = out.length
                    out.append(head).append(body)
                    if (head.isNotEmpty()) {
                        out.setSpan(
                            StyleSpan(Typeface.BOLD), start, start + head.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        out.setSpan(
                            RelativeSizeSpan(0.85f), start, start + head.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    out.setSpan(
                        TypefaceSpan("monospace"), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.setSpan(
                        BackgroundColorSpan(codeBg), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.setSpan(
                        RelativeSizeSpan(0.92f), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    codeRanges.add(start until out.length)
                }
                continue
            }
            out.append(inlineSpans(line, codeBg))
            if (idx < lines.size - 1) out.append('\n')
            idx++
        }
        // Bullet/leading spans must be applied per paragraph after assembly.
        applyBlockSpans(out, codeRanges)
        return out
    }

    private fun inlineSpans(line: String, codeBg: Int): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        // Headers: "## Title" / "### Title".
        val header = Regex("^(#{1,4})\\s+(.*)$").matchEntire(line)
        if (header != null) {
            val level = header.groupValues[1].length
            out.append(header.groupValues[2])
            out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(if (level <= 2) 1.12f else 1.05f), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return out
        }
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.append(buf.toString())
                buf.clear()
            }
        }
        while (i < line.length) {
            if (line.startsWith("**", i)) {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    val start = out.length
                    out.append(line.substring(i + 2, end))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (line[i] == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    val start = out.length
                    out.append(line.substring(i + 1, end))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            buf.append(line[i])
            i++
        }
        flush()
        return out
    }

    private fun applyBlockSpans(out: SpannableStringBuilder, codeRanges: List<IntRange>) {
        // "- " / "* " bullets and "1. " ordered items get leading margins.
        // Walk assembled lines; offsets shift as we only add spans (no text
        // edits here), so plain index math holds. Code ranges are skipped so
        // source text never gains list styling.
        fun inCode(start: Int, end: Int): Boolean =
            codeRanges.any { it.first < end && start < it.last }
        var offset = 0
        for (raw in out.toString().lines()) {
            val line = raw
            val end = offset + line.length
            if (!inCode(offset, end)) {
                val bullet = Regex("^[\\-*]\\s+.+").matches(line)
                val ordered = Regex("^\\d+[.)]\\s+.+").matches(line)
                if (bullet) {
                    out.setSpan(BulletSpan(24), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (ordered) {
                    out.setSpan(LeadingMarginSpan.Standard(48, 24), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            offset = end + 1
        }
    }
}
