package com.customs.ledger.diff;

import com.customs.ledger.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pending differences and their resolution trail. */
@Repository
public class DiscrepancyRepository {

    private final JdbcClient jdbc;

    public DiscrepancyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Draft produced during a close before the close id exists. */
    public record Draft(String kind, String materialCode, String sourceSystem, String sourceItemCode,
                        BigDecimal expected, BigDecimal actual, String message, String detailJson) {
    }

    public void insertAll(long ledgerId, String period, long closeId, Map<String, Long> materialIds,
                          List<Draft> drafts) {
        for (Draft d : drafts) {
            Long materialId = d.materialCode() == null ? null : materialIds.get(d.materialCode());
            jdbc.sql("""
                    INSERT INTO discrepancy (ledger_id, period, close_id, kind, material_id, source_system,
                                             source_item_code, expected, actual, message, detail)
                    VALUES (:l, :p, :c, :k, :m, :s, :ic, :e, :a, :msg, CAST(:det AS jsonb))
                    ON CONFLICT (close_id, kind, COALESCE(source_item_code, ''), COALESCE(material_id, 0))
                        WHERE status IN ('OPEN') DO NOTHING
                    """)
                    .param("l", ledgerId).param("p", period).param("c", closeId).param("k", d.kind())
                    .param("m", materialId).param("s", d.sourceSystem()).param("ic", d.sourceItemCode())
                    .param("e", d.expected()).param("a", d.actual()).param("msg", d.message())
                    .param("det", d.detailJson()).update();
        }
    }

    public record Row(long id, String period, Long closeId, String kind, String material, String sourceSystem,
                      String sourceItemCode, BigDecimal expected, BigDecimal actual, String message,
                      String status, String resolution, String resolutionNote, String resolvedBy,
                      OffsetDateTime resolvedAt, Long adjustmentDocumentId, Long documentId, String detail,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public List<Row> query(long ledgerId, String period, String status, String kind, int limit) {
        return jdbc.sql("""
                SELECT d.id, d.period, d.close_id, d.kind, m.code AS material, d.source_system, d.source_item_code,
                       d.expected, d.actual, d.message, d.status, d.resolution, d.resolution_note, d.resolved_by,
                       d.resolved_at, d.adjustment_document_id, (d.detail->>'documentId') AS document_id,
                       d.detail::text AS detail, d.created_at, d.updated_at
                FROM discrepancy d LEFT JOIN material m ON m.id = d.material_id
                WHERE d.ledger_id = :l
                  AND (CAST(:p AS text) IS NULL OR d.period = CAST(:p AS text))
                  AND (CAST(:s AS text) IS NULL OR d.status = CAST(:s AS text))
                  AND (CAST(:k AS text) IS NULL OR d.kind = CAST(:k AS text))
                ORDER BY d.period DESC, d.id DESC
                LIMIT :lim
                """)
                .param("l", ledgerId).param("p", period).param("s", status).param("k", kind)
                .param("lim", limit)
                .query((rs, i) -> new Row(
                        rs.getLong("id"), rs.getString("period"),
                        (Long) rs.getObject("close_id", Long.class), rs.getString("kind"),
                        rs.getString("material"), rs.getString("source_system"), rs.getString("source_item_code"),
                        rs.getBigDecimal("expected"), rs.getBigDecimal("actual"), rs.getString("message"),
                        rs.getString("status"), rs.getString("resolution"), rs.getString("resolution_note"),
                        rs.getString("resolved_by"),
                        rs.getObject("resolved_at", OffsetDateTime.class),
                        (Long) rs.getObject("adjustment_document_id", Long.class),
                        rs.getString("document_id") == null ? null : Long.valueOf(rs.getString("document_id")),
                        rs.getString("detail"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    public Optional<Row> find(long ledgerId, long id) {
        return query(ledgerId, null, null, null, 10_000).stream().filter(r -> r.id() == id).findFirst();
    }

    public void resolve(long ledgerId, long id, String status, String resolution, String note, String user,
                        Long adjustmentDocumentId) {
        int updated = jdbc.sql("""
                UPDATE discrepancy SET status = :s, resolution = :r, resolution_note = :n, resolved_by = :u,
                       resolved_at = now(), adjustment_document_id = COALESCE(:adj, adjustment_document_id),
                       updated_at = now()
                WHERE id = :id AND ledger_id = :l AND status = 'OPEN'
                """)
                .param("s", status).param("r", resolution).param("n", note).param("u", user)
                .param("adj", adjustmentDocumentId).param("id", id).param("l", ledgerId).update();
        if (updated == 0) {
            throw ApiException.conflict("DISCREPANCY_NOT_OPEN",
                    "discrepancy " + id + " is not open (or does not belong to ledger)");
        }
    }
}
