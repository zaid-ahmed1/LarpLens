package com.larplens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameExtractorTest {
  private fun line(text: String, top: Int, height: Int) = OcrLine(text, top, height)

  @Test
  fun dropsHelloMyNameIsTemplate() {
    val lines = listOf(line("HELLO", 0, 30), line("my name is", 35, 12), line("PRIYA", 60, 50))
    assertEquals("PRIYA", NameExtractor.extract(lines))
  }

  @Test
  fun picksTheBiggestWriting() {
    val lines = listOf(line("Sam Okafor", 10, 48), line("Building a drone startup", 70, 14))
    assertEquals("Sam Okafor", NameExtractor.extract(lines))
  }

  @Test
  fun joinsStackedFirstAndLastName() {
    val lines = listOf(line("CHEN", 80, 40), line("MEI", 20, 44), line("@meichen", 130, 10))
    assertEquals("MEI CHEN", NameExtractor.extract(lines))
  }

  @Test
  fun stripsPunctuationAndDigitsKeepsHyphensAndApostrophes() {
    assertEquals("Jean-Luc O'Neil", NameExtractor.extract(listOf(line("Jean-Luc O'Neil!!", 0, 40))))
    assertEquals("Ana", NameExtractor.extract(listOf(line("Ana 2026", 0, 40))))
  }

  @Test
  fun templateMatchingIgnoresCaseAndSpacing() {
    assertNull(NameExtractor.extract(listOf(line("Hello, my  NAME is:", 0, 40))))
  }

  @Test
  fun nothingNameLike() {
    assertNull(NameExtractor.extract(emptyList()))
    assertNull(NameExtractor.extract(listOf(line("42 :)", 0, 40))))
    assertNull(NameExtractor.extract(listOf(line("J", 0, 40))))
  }

  @Test
  fun normalizeComparesLoosely() {
    assertEquals(NameExtractor.normalize("Zaid."), NameExtractor.normalize("ZAID"))
  }
}
