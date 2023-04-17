package com.composum.chatgpt.base.service.chat.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/** Tests for {@link GPTKeywordServiceImpl}. */
public class GPTKeywordServiceImplTest {

    protected GPTKeywordServiceImpl service = new GPTKeywordServiceImpl();

    @Test
    public void testShortenShortText() {
        String text = "This is a short text.";
        String shortenedText = service.shorten(text, 10);
        assertEquals(text, shortenedText);
    }

    @Test
    public void testShortenLongText() {
        String text = "This is a very long text that should be shortened. Well, not really very long, but long enough.";
        String shortenedText = service.shorten(text, 4);
        assertEquals("This is ... enough.", shortenedText);
    }

    @Test
    public void testShortenTextWithOddMaxWords() {
        String text = "This is a text with odd max words which we shorten.";
        String shortenedText = service.shorten(text, 3);
        assertEquals("This ... shorten.", shortenedText);
    }

    @Test
    public void testShortenTextWithEvenMaxWords() {
        String text = "This is a text with even max words which we shorten.";
        String shortenedText = service.shorten(text, 4);
        assertEquals("This is ... shorten.", shortenedText);
    }

    @Test
    public void testShortenEmptyText() {
        String text = "";
        String shortenedText = service.shorten(text, 10);
        assertEquals(text, shortenedText);
    }

    @Test
    public void testShortenTextWithOddNumberOfWords() {
        String text = "This is a text with odd wordcount.";
        String shortenedText = service.shorten(text, 4);
        assertEquals("This is ... wordcount.", shortenedText);
    }

    @Test
    public void testShortenTextWithEvenNumberOfWords() {
        String text = "This is a text with even number of words.";
        String shortenedText = service.shorten(text, 4);
        assertEquals("This is ... words.", shortenedText);
    }

}
