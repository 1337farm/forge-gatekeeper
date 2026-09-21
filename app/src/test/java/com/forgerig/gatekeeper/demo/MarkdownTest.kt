package com.forgerig.gatekeeper.demo

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTest {

    @Test
    fun `plain text passes through untouched`() {
        val out = Markdown.spannify("Just a plain line.", 0xFF000000.toInt())
        assertEquals("Just a plain line.", out.toString())
        assertEquals(0, out.getSpans(0, out.length, Any::class.java).size)
    }

    @Test
    fun `fenced code block drops fences and gains mono spans`() {
        val out = Markdown.spannify("Use it:\n```javascript\nlet x = 1;\n```\nDone.", 0xFF000000.toInt())
        val text = out.toString()
        assertTrue(!text.contains("```"))
        assertTrue(text.contains("let x = 1;"))
        assertTrue(text.contains("javascript"))
        val mono = out.getSpans(0, out.length, TypefaceSpan::class.java)
        assertTrue(mono.isNotEmpty())
        val bg = out.getSpans(0, out.length, BackgroundColorSpan::class.java)
        assertTrue(bg.isNotEmpty())
    }

    @Test
    fun `unclosed fence renders tail as code`() {
        val out = Markdown.spannify("Intro\n```python\nx = 1\ny = 2", 0xFF000000.toInt())
        assertTrue(out.toString().contains("x = 1"))
        assertTrue(out.getSpans(0, out.length, TypefaceSpan::class.java).isNotEmpty())
    }

    @Test
    fun `bold and inline code span correctly`() {
        val out = Markdown.spannify("This is **bold** and `code` here.", 0xFF000000.toInt())
        assertEquals("This is bold and code here.", out.toString())
        val bold = out.getSpans(0, out.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }
        assertEquals(1, bold.size)
        val mono = out.getSpans(0, out.length, TypefaceSpan::class.java)
        assertEquals(1, mono.size)
    }

    @Test
    fun `headers scale and bullets margin`() {
        val out = Markdown.spannify("## Title\n- item one\n1. first", 0xFF000000.toInt())
        assertTrue(out.toString().contains("Title"))
        assertTrue(out.getSpans(0, out.length, RelativeSizeSpan::class.java).isNotEmpty())
        assertTrue(out.getSpans(0, out.length, BulletSpan::class.java).isNotEmpty())
        assertTrue(out.getSpans(0, out.length, LeadingMarginSpan::class.java).isNotEmpty())
    }

    @Test
    fun `angle brackets survive without html round trip`() {
        val out = Markdown.spannify("Use <div> and a & b.", 0xFF000000.toInt())
        assertEquals("Use <div> and a & b.", out.toString())
    }
}
