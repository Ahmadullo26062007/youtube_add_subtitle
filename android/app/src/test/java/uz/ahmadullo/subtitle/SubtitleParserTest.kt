package uz.ahmadullo.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun parsesSrt() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\nSalom\r\ndunyo\r\n\r\n" +
            "2\r\n00:00:04,000 --> 00:00:05,250\r\n<i>Ikkinchi</i>\r\n"
        val (format, cues) = SubtitleParser.parse(srt)

        assertEquals("srt", format)
        assertEquals(2, cues.size)
        assertEquals(1.5, cues[0].s, 0.0001)
        assertEquals(3.0, cues[0].e, 0.0001)
        assertEquals("Salom\ndunyo", cues[0].t)
        assertEquals(5.25, cues[1].e, 0.0001)
    }

    @Test
    fun parsesVttWithSettingsAndNotes() {
        val vtt = "WEBVTT\n\nNOTE izoh\n\n00:01.000 --> 00:02.000 align:middle\nBir\n\n" +
            "1:00:00.5 --> 1:00:01.000\nIkki\n"
        val (format, cues) = SubtitleParser.parse(vtt)

        assertEquals("vtt", format)
        assertEquals(2, cues.size)
        assertEquals(1.0, cues[0].s, 0.0001)
        assertEquals(3600.5, cues[1].s, 0.0001)
    }

    @Test
    fun enforcesMinimumDuration() {
        val (_, cues) = SubtitleParser.parse("00:00:01,000 --> 00:00:01,000\nX\n")
        assertEquals(1.2, cues[0].e, 0.0001)
    }

    @Test
    fun decodesWindows1251() {
        val bytes = "00:00:01,000 --> 00:00:02,000\nПривет\n".toByteArray(charset("windows-1251"))
        val parsed = SubtitleParser.parseBytes(bytes)

        assertEquals("windows-1251", parsed.encoding)
        assertEquals("Привет", parsed.cues[0].t)
    }

    @Test
    fun decodesUtf8() {
        val parsed = SubtitleParser.parseBytes("00:00:01,000 --> 00:00:02,000\nO‘zbek\n".toByteArray())
        assertEquals("utf-8", parsed.encoding)
        assertEquals("O‘zbek", parsed.cues[0].t)
    }

    @Test
    fun findsActiveAndOverlappingCues() {
        val cues = listOf(
            Cue(0.0, 2.0, "a"),
            Cue(1.0, 5.0, "b"),
            Cue(3.0, 4.0, "c"),
            Cue(6.0, 7.0, "d")
        )

        assertEquals(listOf("a", "b"), SubtitleParser.activeCues(cues, 1.5).map { it.t })
        assertEquals(listOf("b", "c"), SubtitleParser.activeCues(cues, 3.5).map { it.t })
        assertTrue(SubtitleParser.activeCues(cues, 5.5).isEmpty())
        assertTrue(SubtitleParser.activeCues(cues, -1.0).isEmpty())
    }

    @Test
    fun splitsStyledSegments() {
        val segs = SubtitleParser.segments("<v Ali>Salom <i>qalay<b>siz</b></i> {\\i1}&amp; <c.red>ok</c>")

        assertEquals("Salom qalaysiz & ok", segs.joinToString("") { it.text })
        val siz = segs.first { it.text == "siz" }
        assertTrue(siz.italic && siz.bold)
        assertTrue(!segs.last().italic)
    }
}
