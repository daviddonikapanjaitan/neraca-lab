package com.neracalab.backend.ingestion.file;

import java.security.MessageDigest;
import java.sql.Types;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ingestion_file}: uploaded workbooks, stored once per content. The SHA-256 checksum is
 * unique, so uploading a file that is already stored reuses the stored row instead of saving the
 * bytes again (also when two identical uploads arrive at the same time).
 */
@Repository
public class IngestionFileRepository {

    /** Longest stored file name (column VARCHAR(255)). */
    public static final int NAME_LENGTH = 255;

    /**
     * A stored file (without its content).
     *
     * @param fileName name of the upload that stored the content first
     * @param reused   the content was already stored before this upload
     */
    public record StoredFile(long fileId, String fileName, long sizeBytes, String checksumSha256, boolean reused) {
    }

    private final JdbcClient jdbc;

    public IngestionFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the content unless a file with the same checksum exists; returns the stored file either way. */
    public StoredFile store(byte[] content, String fileName, String contentType) {
        String checksum = sha256(content);
        Optional<Long> inserted = jdbc.sql("""
                        INSERT INTO ingestion_file (file_name, content_type, size_bytes, checksum_sha256, content)
                        VALUES (:name, :contentType, :size, :checksum, :content)
                        ON CONFLICT (checksum_sha256) DO NOTHING
                        RETURNING file_id""")
                .param("name", limit(fileName))
                .param("contentType", contentType == null ? null : limit(contentType), Types.VARCHAR)
                .param("size", (long) content.length)
                .param("checksum", checksum)
                .param("content", content)
                .query(Long.class)
                .optional();
        if (inserted.isPresent()) {
            return new StoredFile(inserted.get(), limit(fileName), content.length, checksum, false);
        }
        return findByChecksum(checksum)
                .map(f -> new StoredFile(f.fileId(), f.fileName(), f.sizeBytes(), f.checksumSha256(), true))
                .orElseThrow(() -> new IllegalStateException("ingestion_file with checksum " + checksum + " vanished"));
    }

    public Optional<StoredFile> findByChecksum(String checksum) {
        return jdbc.sql("""
                        SELECT file_id, file_name, size_bytes, checksum_sha256 FROM ingestion_file
                        WHERE checksum_sha256 = :checksum""")
                .param("checksum", checksum)
                .query((rs, i) -> new StoredFile(rs.getLong("file_id"), rs.getString("file_name"),
                        rs.getLong("size_bytes"), rs.getString("checksum_sha256"), true))
                .optional();
    }

    /** The bytes of a stored file. */
    public Optional<byte[]> content(long fileId) {
        return jdbc.sql("SELECT content FROM ingestion_file WHERE file_id = :id")
                .param("id", fileId)
                .query((rs, i) -> rs.getBytes("content"))
                .optional();
    }

    /** Lower-case hex SHA-256, as stored in {@code checksum_sha256}. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String limit(String text) {
        return text.length() <= NAME_LENGTH ? text : text.substring(0, NAME_LENGTH);
    }
}
