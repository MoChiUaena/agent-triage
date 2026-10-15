package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class KnowledgeFilesTest {
    @TempDir Path directory;
    KnowledgeFiles files = new KnowledgeFiles();

    @Test
    void boundsEligibleFilesBytesTraversalAndDepth() throws Exception {
        for (int i = 0; i < 101; i++) Files.writeString(directory.resolve("f" + i + ".md"), "text");
        var read = files.read(directory.toString(), () -> false);
        assertThat(read.documents()).hasSize(100);
        assertThat(read.complete()).isFalse();
        assertThat(read.exclusions()).anyMatch(e -> e.reason().equals("FILE_LIMIT"));
        Path nested = directory;
        for (int i = 0; i < 22; i++) nested = Files.createDirectories(nested.resolve("nested"));
        Files.writeString(nested.resolve("deep.md"), "deep");
        assertThat(files.read(directory.toString(), () -> false).exclusions())
                .anyMatch(e -> e.reason().equals("DEPTH_LIMIT"));
    }

    @Test
    void aggregateAndChunkCapsAreHonest() throws Exception {
        for (int i = 0; i < 12; i++)
            Files.writeString(directory.resolve("large" + i + ".txt"), "a".repeat(MAX_FILE_BYTES));
        var read = files.read(directory.toString(), () -> false);
        assertThat(read.documents().stream().mapToInt(Document::bytes).sum())
                .isLessThanOrEqualTo(MAX_BYTES);
        assertThat(
                        read.documents().stream()
                                .mapToInt(
                                        d ->
                                                KnowledgeFiles.chunks(UUID.randomUUID(), d.text())
                                                        .size())
                                .sum())
                .isLessThanOrEqualTo(MAX_CHUNKS);
        assertThat(read.complete()).isFalse();
    }

    @Test
    void rejectsCredentialBinaryLinksRootAndTraversal() throws Exception {
        assertThatThrownBy(() -> files.canonical(directory.getRoot().toString()))
                .hasMessageContaining("根");
        assertThatThrownBy(() -> KnowledgeFiles.safePath("docs/../key.md"))
                .hasMessageContaining("跳转");
        assertThatThrownBy(
                        () ->
                                KnowledgeFiles.document(
                                        "x.md", "Authorization: Bearer must-not-store".getBytes()))
                .hasMessageContaining("CREDENTIAL");
        assertThatThrownBy(() -> KnowledgeFiles.document("x.txt", new byte[] {0}))
                .hasMessageContaining("BINARY");
        Path target = Files.createTempFile("knowledge-outside-", ".md");
        try {
            Files.writeString(target, "external");
            try {
                Files.createSymbolicLink(directory.resolve("link.md"), target);
            } catch (FileSystemException | UnsupportedOperationException e) {
                return;
            }
            assertThat(files.read(directory.toString(), () -> false).documents()).isEmpty();
            assertThat(files.read(directory.toString(), () -> false).exclusions())
                    .anyMatch(e -> e.reason().contains("LINK"));
        } finally {
            Files.delete(target);
        }
    }

    @Test
    void chunkPositionsReconstructUnicodeAndLineNumbers() {
        String text = "first\n" + "😀".repeat(2000) + "\nlast\n";
        var chunks = KnowledgeFiles.chunks(UUID.randomUUID(), text);
        assertThat(chunks.stream().map(Chunk::text).reduce("", String::concat)).isEqualTo(text);
        for (Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(2000);
            assertThat(text.substring(c.startOffset(), c.endOffset())).isEqualTo(c.text());
            assertThat(c.startLine())
                    .isEqualTo(
                            1
                                    + (int)
                                            text.substring(0, c.startOffset())
                                                    .chars()
                                                    .filter(x -> x == '\n')
                                                    .count());
        }
    }

    @Test
    void rejectsMoreThanFiveThousandVisitedEntriesWithoutFollowingLinks() throws Exception {
        for (int i = 0; i < 5001; i++) Files.createFile(directory.resolve("ignored" + i + ".bin"));
        var read = files.read(directory.toString(), () -> false);
        assertThat(read.visited()).isLessThanOrEqualTo(MAX_ENTRIES);
        assertThat(read.complete()).isFalse();
        assertThat(read.exclusions())
                .anyMatch(
                        e ->
                                e.reason().equals("ENTRY_LIMIT")
                                        || e.reason().equals("MORE_EXCLUSIONS"));
    }

    @Test
    void rejectedContentStillConsumesTheCandidateReadBudget() throws Exception {
        for (int i = 0; i < 101; i++)
            Files.writeString(
                    directory.resolve("candidate" + i + ".md"),
                    "Authorization: Bearer rejected-value");
        var read = files.read(directory.toString(), () -> false);
        assertThat(read.documents()).isEmpty();
        assertThat(read.complete()).isFalse();
        assertThat(read.exclusions())
                .anyMatch(
                        e ->
                                e.reason().equals("FILE_LIMIT")
                                        || e.reason().equals("MORE_EXCLUSIONS"));
    }
}
