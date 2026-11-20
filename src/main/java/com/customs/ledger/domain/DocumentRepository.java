package com.customs.ledger.domain;

import com.customs.ledger.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public DocumentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static final RowMapper<Doc> DOC = (rs, i) -> new Doc(
            rs.getLong("id"),
            DocType.valueOf(rs.getString("doc_type")),
            rs.getString("source_system"),
            rs.getString("external_id"),
            rs.getInt("source_version"),
            rs.getObject("business_time", OffsetDateTime.class).toInstant(),
            rs.getObject("received_at", OffsetDateTime.class).toInstant(),
            rs.getString("payload_text"),
            rs.getString("payload_hash"),
            (Long) rs.getObject("supersedes_document_id", Long.class),
            (Long) rs.getObject("void_of_document_id", Long.class),
            rs.getString("correction_reason"),
            rs.getBoolean("effective"),
            null);

    private static final String DOC_COLUMNS = """
            id, doc_type, source_system, external_id, source_version, business_time, received_at,
            payload_text, payload_hash, supersedes_document_id, void_of_document_id, correction_reason, effective
            """;

    public static Doc withPayload(Doc doc, ObjectMapper mapper, String payloadText) {
        try {
            return new Doc(doc.id(), doc.type(), doc.sourceSystem(), doc.externalId(), doc.sourceVersion(),
                    doc.businessTime(), doc.receivedAt(), doc.payloadJson(), doc.payloadHash(),
                    doc.supersedesId(), doc.voidOfId(), doc.correctionReason(), doc.effective(),
                    mapper.readTree(payloadText));
        } catch (Exception e) {
            throw new IllegalStateException("stored payload is not valid JSON for document " + doc.id(), e);
        }
    }

    public Doc insert(long ledgerId, DocType type, String sourceSystem, String externalId, int sourceVersion,
                      Instant businessTime, String payloadText, String requestOriginal, String payloadHash,
                      String idempotencyKey, Long supersedesId, Long voidOfId, String correctionReason) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO document (ledger_id, doc_type, source_system, external_id, source_version,
                                      business_time, payload_text, payload, request_original, payload_hash, idempotency_key,
                                      supersedes_document_id, void_of_document_id, correction_reason)
                VALUES (:l, :t, :s, :e, :v, :bt, :pt, CAST(:pt AS jsonb), :ro, :ph, :ik, :sup, :vid, :cr)
                """)
                .param("l", ledgerId).param("t", type.name()).param("s", sourceSystem).param("e", externalId)
                .param("v", sourceVersion).param("bt", OffsetDateTime.ofInstant(businessTime, ZoneOffset.UTC))
                .param("pt", payloadText).param("ro", requestOriginal).param("ph", payloadHash).param("ik", idempotencyKey)
                .param("sup", supersedesId).param("vid", voidOfId).param("cr", correctionReason)
                .update(keys, "id");
        return findById(keys.getKey().longValue()).map(d -> withPayload(d, mapper, d.payloadJson())).orElseThrow();
    }

    public Optional<Doc> findById(long id) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + " FROM document WHERE id = :id")
                .param("id", id).query(DOC).optional();
    }

    public Doc requireById(long id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "unknown document " + id));
    }

    public Optional<Doc> findVersion(long ledgerId, String sourceSystem, DocType type, String externalId, int version) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + """
                        FROM document
                        WHERE ledger_id = :l AND source_system = :s AND doc_type = :t
                          AND external_id = :e AND source_version = :v
                        """)
                .param("l", ledgerId).param("s", sourceSystem).param("t", type.name())
                .param("e", externalId).param("v", version).query(DOC).optional();
    }

    public Optional<Doc> findByIdempotencyKey(long ledgerId, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + DOC_COLUMNS + " FROM document WHERE ledger_id = :l AND idempotency_key = :k")
                .param("l", ledgerId).param("k", key).query(DOC).optional();
    }

    public List<Doc> versionsOf(long ledgerId, String sourceSystem, DocType type, String externalId) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + """
                        FROM document
                        WHERE ledger_id = :l AND source_system = :s AND doc_type = :t AND external_id = :e
                        ORDER BY source_version
                        """)
                .param("l", ledgerId).param("s", sourceSystem).param("t", type.name()).param("e", externalId)
                .query(DOC).list();
    }

    public Optional<Doc> latestVersion(long ledgerId, String sourceSystem, DocType type, String externalId) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + """
                        FROM document
                        WHERE ledger_id = :l AND source_system = :s AND doc_type = :t AND external_id = :e
                        ORDER BY source_version DESC LIMIT 1
                        """)
                .param("l", ledgerId).param("s", sourceSystem).param("t", type.name()).param("e", externalId)
                .query(DOC).optional();
    }

    /** All document versions currently marked effective in the ledger. */
    public List<Doc> effectiveDocuments(long ledgerId) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + " FROM document WHERE ledger_id = :l AND effective = TRUE ORDER BY business_time, id")
                .param("l", ledgerId).query(DOC).list();
    }

    /** Materialises every effective document of a period; payload is decoded lazily by the caller. */
    public List<Doc> effectiveDocuments(long ledgerId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + """
                        FROM document
                        WHERE ledger_id = :l AND effective = TRUE
                          AND business_time >= :f AND business_time < :t
                        ORDER BY business_time, id
                        """)
                .param("l", ledgerId).param("f", OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC))
                .param("t", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC))
                .query(DOC).list();
    }

    public JsonNode payload(Doc doc) {
        try {
            return mapper.readTree(doc.payloadJson());
        } catch (Exception e) {
            throw new IllegalStateException("stored payload is not valid JSON for document " + doc.id(), e);
        }
    }

    public void setEffective(long documentId, boolean effective) {
        jdbc.sql("UPDATE document SET effective = :e WHERE id = :id")
                .param("e", effective).param("id", documentId).update();
    }

    /** Marks every earlier version of the same business key as not effective. */
    public void supersedeEarlier(long ledgerId, String sourceSystem, DocType type, String externalId, int version) {
        jdbc.sql("""
                UPDATE document SET effective = FALSE
                WHERE ledger_id = :l AND source_system = :s AND doc_type = :t
                  AND external_id = :e AND source_version < :v AND effective = TRUE
                """)
                .param("l", ledgerId).param("s", sourceSystem).param("t", type.name())
                .param("e", externalId).param("v", version).update();
    }

    public List<Doc> allVersions(long ledgerId) {
        return jdbc.sql("SELECT " + DOC_COLUMNS + " FROM document WHERE ledger_id = :l ORDER BY id")
                .param("l", ledgerId).query(DOC).list();
    }

    // ---------------------------------------------------------------- lines

    public void insertLine(long documentId, DocLine line) {
        jdbc.sql("""
                INSERT INTO document_line (document_id, line_no, source_item_code, source_unit, source_quantity,
                                           batch_no, work_order, material_id, base_quantity, alias_id, conversion_id,
                                           output_material_code)
                VALUES (:d, :n, :ic, :u, :q, :b, :w, :m, :bq, :a, :c, :om)
                """)
                .param("d", documentId).param("n", line.lineNo()).param("ic", line.sourceItemCode())
                .param("u", line.sourceUnit()).param("q", line.sourceQuantity()).param("b", line.batchNo())
                .param("w", line.workOrder()).param("m", line.materialId()).param("bq", line.baseQuantity())
                .param("a", line.aliasId()).param("c", line.conversionId())
                .param("om", line.outputMaterialCode()).update();
    }

    public List<DocLine> lines(long documentId) {
        return jdbc.sql("""
                SELECT id, document_id, line_no, source_item_code, source_unit, source_quantity, batch_no,
                       work_order, material_id, base_quantity, alias_id, conversion_id, output_material_code
                FROM document_line WHERE document_id = :d ORDER BY line_no
                """).param("d", documentId).query(LINE).list();
    }

    public List<DocLine> linesOfDocuments(List<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, document_id, line_no, source_item_code, source_unit, source_quantity, batch_no,
                       work_order, material_id, base_quantity, alias_id, conversion_id, output_material_code
                FROM document_line WHERE document_id IN (:ids) ORDER BY document_id, line_no
                """).param("ids", documentIds).query(LINE).list();
    }

    private static final RowMapper<DocLine> LINE = (rs, i) -> new DocLine(
            rs.getLong("id"), rs.getLong("document_id"), rs.getInt("line_no"),
            rs.getString("source_item_code"), rs.getString("source_unit"),
            rs.getBigDecimal("source_quantity"), rs.getString("batch_no"), rs.getString("work_order"),
            (Long) rs.getObject("material_id", Long.class), rs.getBigDecimal("base_quantity"),
            (Long) rs.getObject("alias_id", Long.class), (Long) rs.getObject("conversion_id", Long.class),
            rs.getString("output_material_code"));
}
