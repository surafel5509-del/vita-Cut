package com.vitacut.core.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleImporterTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        First line

        2
        00:00:04,000 --> 00:00:06,000
        Second line
        wrapped
    """.trimIndent()

    private val vtt = """
        WEBVTT
        Kind: captions
        Language: en

        NOTE this is a comment block

        intro
        00:01.000 --> 00:03.500
        <b>First</b> line

        01:04.000 --> 01:06.000 position:10%
        Second line
    """.trimIndent()

    @Test
    fun `detects formats`() {
        assertEquals(SubtitleFormat.SRT, SubtitleImporter.detectFormat(srt))
        assertEquals(SubtitleFormat.VTT, SubtitleImporter.detectFormat(vtt))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleImporter.detectFormat("hello world"))
    }

    @Test
    fun `imports srt with multi-line cues`() {
        val captions = SubtitleImporter.import(srt)
        assertEquals(2, captions.size)
        assertEquals(1_000_000L, captions[0].startUs)
        assertEquals(3_500_000L, captions[0].endUs)
        assertEquals("First line", captions[0].text)
        assertEquals("Second line\nwrapped", captions[1].text)
    }

    @Test
    fun `imports vtt stripping header, notes, ids, tags and cue settings`() {
        val captions = SubtitleImporter.import(vtt)
        assertEquals(2, captions.size)
        assertEquals(1_000_000L, captions[0].startUs)
        assertEquals("First line", captions[0].text) // <b> stripped
        assertEquals(64_000_000L, captions[1].startUs) // 01:04.000 → 64s
        assertEquals(66_000_000L, captions[1].endUs)
    }

    @Test
    fun `vtt timestamps with hours are supported`() {
        val content = """
            WEBVTT

            00:01:02.500 --> 00:01:04.250
            Hour form
        """.trimIndent()
        val captions = SubtitleImporter.import(content)
        assertEquals(1, captions.size)
        assertEquals(62_500_000L, captions[0].startUs)
        assertEquals(64_250_000L, captions[0].endUs)
    }

    @Test
    fun `garbage input yields empty list instead of throwing`() {
        assertTrue(SubtitleImporter.import("not subtitles at all").isEmpty())
        assertTrue(SubtitleImporter.import("").isEmpty())
    }

    @Test
    fun `srt round trip preserves cues`() {
        val captions = SubtitleImporter.import(srt)
        val exported = SubtitleImporter.exportSrt(captions)
        val reimported = SubtitleImporter.import(exported)
        assertEquals(captions.map { it.startUs to it.endUs }, reimported.map { it.startUs to it.endUs })
        assertEquals(captions.map { it.text }, reimported.map { it.text })
    }

    @Test
    fun `vtt export has the webvtt header`() {
        val exported = SubtitleImporter.exportVtt(SubtitleImporter.import(srt))
        assertTrue(exported.startsWith("WEBVTT"))
        assertTrue(exported.contains("-->"))
    }
}
