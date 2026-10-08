package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.TextChunker.Chunk;
import com.neracalab.backend.rag.TextChunker.Part;

class TextChunkerTest {

    private static String words(String prefix, int count) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add(prefix + i);
        }
        return String.join(" ", words);
    }

    @Test
    void cleansWhitespaceAndDropsBlankLines() {
        assertThat(TextChunker.cleanLines("  a   b \t c \n\n   \n d  e\r\nf  "))
                .containsExactly("a b c", "d e", "f");
    }

    @Test
    void shortTextIsOneChunkWithItsPage() {
        List<Chunk> chunks = new TextChunker(1500, 200).chunk(List.of(new Part("Laba bersih\n\nnaik 12%", 3)));
        assertThat(chunks).containsExactly(new Chunk(0, "Laba bersih\nnaik 12%", 3, 3));
    }

    @Test
    void emptyTextHasNoChunks() {
        assertThat(new TextChunker(1500, 200).chunk(List.of(new Part("  \n \t", 1), new Part(null, 2)))).isEmpty();
    }

    @Test
    void chunksStayWithinTheLimitOverlapAndKeepEveryWord() {
        List<Part> parts = new ArrayList<>();
        for (int page = 1; page <= 6; page++) {
            for (int line = 0; line < 20; line++) {
                parts.add(new Part(words("p" + page + "l" + line + "w", 8), page));
            }
        }
        TextChunker chunker = new TextChunker(500, 100);
        List<Chunk> chunks = chunker.chunk(parts);

        assertThat(chunks).hasSizeGreaterThan(10);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            assertThat(c.index()).isEqualTo(i);
            assertThat(c.text().length()).isLessThanOrEqualTo(500);
            assertThat(c.pageFrom()).isLessThanOrEqualTo(c.pageTo());
            if (i > 0) {
                // the chunk starts with the end of the previous one (a whole word)
                String previous = chunks.get(i - 1).text();
                String firstWord = c.text().split("[ \n]")[0];
                assertThat(previous).contains(firstWord);
                assertThat(c.pageFrom()).isGreaterThanOrEqualTo(chunks.get(i - 1).pageFrom());
            }
        }
        assertThat(chunks.getFirst().pageFrom()).isEqualTo(1);
        assertThat(chunks.getLast().pageTo()).isEqualTo(6);
        String all = String.join("\n", chunks.stream().map(Chunk::text).toList());
        for (int page = 1; page <= 6; page++) {
            for (int line = 0; line < 20; line++) {
                for (int w = 0; w < 8; w++) {
                    assertThat(all).contains("p" + page + "l" + line + "w" + w);
                }
            }
        }
    }

    @Test
    void longLinesAreCutAtWordBoundaries() {
        String line = words("kata", 400);      // about 3,000 characters on one line
        List<Chunk> chunks = new TextChunker(500, 100).chunk(List.of(new Part(line, 1)));
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text().length()).isLessThanOrEqualTo(500);
            assertThat(c.text()).doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(c.text().split("[ \n]")).allSatisfy(w -> assertThat(w).matches("kata\\d+"));
        });
        assertThat(String.join(" ", chunks.stream().map(Chunk::text).toList())).contains("kata0 ", "kata399");
    }

    @Test
    void wordLongerThanTheLimitIsCut() {
        assertThat(TextChunker.split("x".repeat(25), 10)).containsExactly("x".repeat(10), "x".repeat(10), "x".repeat(5));
    }

    @Test
    void rejectsUnusableSettings() {
        assertThatThrownBy(() -> new TextChunker(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(1000, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(1000, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
