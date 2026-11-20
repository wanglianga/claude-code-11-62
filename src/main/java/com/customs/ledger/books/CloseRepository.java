package com.customs.ledger.books;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Canonical;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CloseRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public CloseRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static final RowMapper<Close> CLOSE = (rs, i) -> new Close(
            rs.getLong("id"), rs.getString("ledger_code"), rs.getString("period"), rs.getInt("version"),
            rs.getString("status"), rs.getString("mode"), rs.getString("algorithm_version"),
            (Long) rs.getObject("prior_close_id", Long.class),
            (Long) rs.getObject("adjustment_of_close_id", Long.class),
            rs.getString("input_hash"), rs.getString("result_hash"), rs.getInt("document_count"),
            rs.getObject("closed_at", OffsetDateTime.class), rs.getString("closed_by"), rs.getString("note"));

    private static final String CLOSE_COLUMNS = """
            p.id, l.code AS ledger_code, p.period, p.version, p.status, p.mode, p.algorithm_version,
            p.prior_close_id, p.adjustment_of_close_id, p.input_hash, p.result_hash, p.document_count,
            p.closed_at, p.closed_by, p.note
            """;

    public Optional<Close> findById(long id) {
        return jdbc.sql("SELECT " + CLOSE_COLUMNS + " FROM period_close p JOIN ledger l ON l.id = p.ledger_id WHERE p.id = :id")
                .param("id", id).query(CLOSE).optional();
    }

    public Close requireById(long id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("CLOSE_NOT_FOUND", "unknown close " + id));
    }

    public Optional<Close> latest(long ledgerId, String period) {
        return jdbc.sql("SELECT " + CLOSE_COLUMNS + """
                        FROM period_close p JOIN ledger l ON l.id = p.ledger_id
                        WHERE p.ledger_id = :l AND p.period = :p
                        ORDER BY p.version DESC LIMIT 1
                        """)
                .param("l", ledgerId).param("p", period).query(CLOSE).optional();
    }

    public Optional<Close> latestClosed(long ledgerId, String period) {
        return latest(ledgerId, period).filter(c -> !c.status().equals("STALE"));
    }

    public List<Close> history(long ledgerId, String period) {
        return jdbc.sql("SELECT " + CLOSE_COLUMNS + """
                        FROM period_close p JOIN ledger l ON l.id = p.ledger_id
                        WHERE p.ledger_id = :l AND p.period = :p
                        ORDER BY p.version DESC
                        """)
                .param("l", ledgerId).param("p", period).query(CLOSE).list();
    }

    public int nextVersion(long ledgerId, String period) {
        return jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM period_close WHERE ledger_id = :l AND period = :p")
                .param("l", ledgerId).param("p", period).query(Integer.class).single();
    }

    public long insert(long ledgerId, String period, int version, String mode, String algorithmVersion,
                       Long priorCloseId, Long adjustmentOfCloseId, String inputHash, String resultHash,
                       String snapshotJson, int documentCount, String closedBy, String note) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO period_close (ledger_id, period, version, status, mode, algorithm_version,
                                          prior_close_id, adjustment_of_close_id, input_hash, result_hash,
                                          snapshot, document_count, closed_by, note)
                VALUES (:l, :p, :v, 'CLOSED', :m, :a, :prior, :adj, :ih, :rh, CAST(:snap AS jsonb), :dc, :by, :note)
                """)
                .param("l", ledgerId).param("p", period).param("v", version).param("m", mode)
                .param("a", algorithmVersion).param("prior", priorCloseId).param("adj", adjustmentOfCloseId)
                .param("ih", inputHash).param("rh", resultHash).param("snap", snapshotJson)
                .param("dc", documentCount).param("by", closedBy).param("note", note)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void markSuperseded(long closeId) {
        jdbc.sql("UPDATE period_close SET status = 'SUPERSEDED' WHERE id = :id AND status = 'CLOSED'")
                .param("id", closeId).update();
    }

    public Snapshot snapshot(long closeId) {
        String json = jdbc.sql("SELECT snapshot::text FROM period_close WHERE id = :id")
                .param("id", closeId).query(String.class).single();
        try {
            return mapper.readValue(json, Snapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored snapshot is unreadable for close " + closeId, e);
        }
    }

    // ---------------------------------------------------------------- results

    public void insertResults(long closeId, Map<String, Long> materialIds, Computation computation) {
        for (Computation.MaterialResult r : computation.materials()) {
            Long materialId = materialIds.get(r.materialCode());
            if (materialId == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO close_material_result (close_id, material_id, opening_quantity, inbound_quantity,
                        consumed_quantity, outbound_quantity, outbound_content, adjust_quantity, closing_quantity,
                        wip_opening, wip_closing, cumulative_consumed, cumulative_outbound_content, allocation_count)
                    VALUES (:c, :m, :o, :i, :u, :ob, :oc, :ad, :cl, :wo, :wc, :cc, :coc, :ac)
                    """)
                    .param("c", closeId).param("m", materialId).param("o", r.opening()).param("i", r.inbound())
                    .param("u", r.consumed()).param("ob", r.outbound()).param("oc", r.outboundContent())
                    .param("ad", r.adjust()).param("cl", r.closing()).param("wo", r.wipOpening())
                    .param("wc", r.wipClosing()).param("cc", r.cumulativeConsumed())
                    .param("coc", r.cumulativeOutboundContent()).param("ac", r.allocationCount()).update();
        }
    }

    public void insertAllocations(long closeId, long ledgerId, Map<String, Long> materialIds,
                                  List<Computation.AllocationRow> rows) {
        for (Computation.AllocationRow row : rows) {
            Long materialId = materialIds.get(row.materialCode());
            if (materialId == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO close_batch_allocation (close_id, ledger_id, material_id, lot_key, batch_no,
                        pool, movement_type, document_id, line_no, work_order, quantity, allocation_seq, active)
                    VALUES (:c, :l, :m, :lk, :b, :pool, :mt, :d, :ln, :wo, :q, :seq, TRUE)
                    """)
                    .param("c", closeId).param("l", ledgerId).param("m", materialId).param("lk", row.lotKey())
                    .param("b", row.batchNo()).param("pool", row.pool()).param("mt", row.movementType())
                    .param("d", row.documentId()).param("ln", row.lineNo()).param("wo", row.workOrder())
                    .param("q", row.quantity()).param("seq", row.allocationSeq()).update();
        }
    }

    public void insertLotStates(long closeId, Map<String, Long> materialIds, List<Computation.LotState> lots) {
        for (Computation.LotState lot : lots) {
            Long materialId = materialIds.get(lot.materialCode());
            if (materialId == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO close_lot_state (close_id, lot_key, material_id, batch_no, pool, quantity, remaining,
                        business_date, source_document_id, source_type)
                    VALUES (:c, :lk, :m, :b, :pool, :q, :r, :d, :doc, :st)
                    """)
                    .param("c", closeId).param("lk", lot.lotKey()).param("m", materialId).param("b", lot.batchNo())
                    .param("pool", lot.pool()).param("q", lot.quantity()).param("r", lot.remaining())
                    .param("d", lot.businessDate()).param("doc", lot.sourceDocumentId())
                    .param("st", lot.sourceType()).update();
        }
    }

    public void insertDocuments(long closeId, List<Snapshot.SnapshotDoc> docs) {
        for (Snapshot.SnapshotDoc doc : docs) {
            jdbc.sql("""
                    INSERT INTO close_document (close_id, document_id, source_version, doc_type, external_id, business_time)
                    VALUES (:c, :d, :v, :t, :e, :bt)
                    """)
                    .param("c", closeId).param("d", doc.id()).param("v", doc.sourceVersion())
                    .param("t", doc.docType()).param("e", doc.externalId())
                    .param("bt", OffsetDateTime.ofInstant(doc.businessTime(), ZoneOffset.UTC)).update();
        }
    }

    public void insertChanges(long closeId, List<Computation.ChangeDraft> changes) {
        for (Computation.ChangeDraft change : changes) {
            jdbc.sql("""
                    INSERT INTO close_change (close_id, kind, document_id, prior_document_id, doc_type,
                                              external_id, business_time, detail)
                    VALUES (:c, :k, :d, :pd, :t, :e, :bt, CAST(:det AS jsonb))
                    """)
                    .param("c", closeId).param("k", change.kind()).param("d", change.documentId())
                    .param("pd", change.priorDocumentId()).param("t", change.docType()).param("e", change.externalId())
                    .param("bt", change.businessTime() == null ? null
                            : OffsetDateTime.ofInstant(change.businessTime(), ZoneOffset.UTC))
                    .param("det", json(change.detail())).update();
        }
    }

    public void insertMaterialAdjustments(long closeId, Map<String, Long> materialIds,
                                          List<Computation.AdjustmentDraft> adjustments) {
        for (Computation.AdjustmentDraft adj : adjustments) {
            Long materialId = materialIds.get(adj.materialCode());
            if (materialId == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO close_material_adjustment (close_id, material_id, quantity_delta, reason)
                    VALUES (:c, :m, :q, :r)
                    ON CONFLICT (close_id, material_id) DO UPDATE SET quantity_delta = EXCLUDED.quantity_delta
                    """)
                    .param("c", closeId).param("m", materialId).param("q", adj.delta()).param("r", adj.reason())
                    .update();
        }
    }

    // ---------------------------------------------------------------- reads for a close

    public List<CloseView.MaterialBalance> materialBalances(long closeId) {
        return jdbc.sql("""
                SELECT m.code, m.name, m.base_unit, r.opening_quantity, r.inbound_quantity, r.consumed_quantity,
                       r.outbound_quantity, r.outbound_content, r.adjust_quantity, r.closing_quantity,
                       r.wip_opening, r.wip_closing, r.cumulative_consumed, r.cumulative_outbound_content,
                       r.allocation_count
                FROM close_material_result r JOIN material m ON m.id = r.material_id
                WHERE r.close_id = :c ORDER BY m.code
                """).param("c", closeId).query((rs, i) -> new CloseView.MaterialBalance(
                rs.getString("code"), rs.getString("name"), rs.getString("base_unit"),
                rs.getBigDecimal("opening_quantity"), rs.getBigDecimal("inbound_quantity"),
                rs.getBigDecimal("consumed_quantity"), rs.getBigDecimal("outbound_quantity"),
                rs.getBigDecimal("outbound_content"), rs.getBigDecimal("adjust_quantity"),
                rs.getBigDecimal("closing_quantity"), rs.getBigDecimal("wip_opening"),
                rs.getBigDecimal("wip_closing"), rs.getBigDecimal("cumulative_consumed"),
                rs.getBigDecimal("cumulative_outbound_content"), rs.getInt("allocation_count"))).list();
    }

    public List<CloseView.BatchAllocation> allocations(long closeId, String materialCode, String lotKey) {
        return jdbc.sql("""
                SELECT m.code, a.lot_key, a.batch_no, a.pool, a.movement_type, a.document_id, a.line_no,
                       a.work_order, a.quantity, a.allocation_seq
                FROM close_batch_allocation a JOIN material m ON m.id = a.material_id
                WHERE a.close_id = :c
                  AND (CAST(:mc AS text) IS NULL OR m.code = CAST(:mc AS text))
                  AND (CAST(:lk AS text) IS NULL OR a.lot_key = CAST(:lk AS text))
                ORDER BY m.code, a.allocation_seq
                """).param("c", closeId).param("mc", materialCode).param("lk", lotKey)
                .query((rs, i) -> new CloseView.BatchAllocation(
                        rs.getString("code"), rs.getString("lot_key"), rs.getString("batch_no"),
                        rs.getString("pool"), rs.getString("movement_type"),
                        (Long) rs.getObject("document_id", Long.class), rs.getInt("line_no"),
                        rs.getString("work_order"), rs.getBigDecimal("quantity"),
                        rs.getInt("allocation_seq"))).list();
    }

    public List<CloseView.ChangeView> changes(long closeId) {
        return jdbc.sql("""
                SELECT kind, document_id, prior_document_id, doc_type, external_id, business_time, detail::text
                FROM close_change WHERE close_id = :c ORDER BY id
                """).param("c", closeId).query((rs, i) -> new CloseView.ChangeView(
                rs.getString("kind"), (Long) rs.getObject("document_id", Long.class),
                (Long) rs.getObject("prior_document_id", Long.class), rs.getString("doc_type"),
                rs.getString("external_id"),
                rs.getObject("business_time", OffsetDateTime.class) == null
                        ? null : rs.getObject("business_time", OffsetDateTime.class).toString(),
                rs.getString("detail"))).list();
    }

    public List<CloseView.AdjustmentView> adjustments(long closeId) {
        return jdbc.sql("""
                SELECT m.code, a.quantity_delta, a.reason
                FROM close_material_adjustment a JOIN material m ON m.id = a.material_id
                WHERE a.close_id = :c ORDER BY m.code
                """).param("c", closeId).query((rs, i) -> new CloseView.AdjustmentView(
                rs.getString("code"), rs.getBigDecimal("quantity_delta"), rs.getString("reason"))).list();
    }

    public List<Computation.LotState> lotStates(long closeId) {
        return jdbc.sql("""
                SELECT m.code, s.lot_key, s.batch_no, s.pool, s.quantity, s.remaining, s.business_date,
                       s.source_document_id, s.source_type
                FROM close_lot_state s JOIN material m ON m.id = s.material_id
                WHERE s.close_id = :c ORDER BY m.code, s.lot_key
                """).param("c", closeId).query((rs, i) -> new Computation.LotState(
                rs.getString("lot_key"), rs.getString("code"), rs.getString("batch_no"), rs.getString("pool"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("remaining"),
                rs.getObject("business_date", LocalDate.class),
                (Long) rs.getObject("source_document_id", Long.class), rs.getString("source_type"))).list();
    }

    public Map<String, Snapshot.PriorTotal> priorTotals(long closeId) {
        Map<String, Snapshot.PriorTotal> totals = new HashMap<>();
        jdbc.sql("""
                SELECT m.code, r.cumulative_consumed, r.cumulative_outbound_content
                FROM close_material_result r JOIN material m ON m.id = r.material_id
                WHERE r.close_id = :c
                """).param("c", closeId).query(rs -> {
            totals.put(rs.getString("code"), new Snapshot.PriorTotal(rs.getString("code"),
                    rs.getBigDecimal("cumulative_consumed"), rs.getBigDecimal("cumulative_outbound_content")));
        });
        return totals;
    }

    public List<Long> documentIds(long closeId) {
        return jdbc.sql("SELECT document_id FROM close_document WHERE close_id = :c")
                .param("c", closeId).query(Long.class).list();
    }

    public Map<Long, Integer> documentVersions(long closeId) {
        Map<Long, Integer> versions = new HashMap<>();
        jdbc.sql("SELECT document_id, source_version FROM close_document WHERE close_id = :c")
                .param("c", closeId).query(rs -> {
            versions.put(rs.getLong("document_id"), rs.getInt("source_version"));
        });
        return versions;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String snapshotJson(Snapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record InventoryRow(String material, String batchNo, String pool, String reference,
                               BigDecimal quantity, BigDecimal remaining) {
    }

    /** Per-batch stock and work-in-process position of a close. */
    public List<InventoryRow> inventory(long closeId) {
        return jdbc.sql("""
                SELECT m.code, s.batch_no, s.pool,
                       COALESCE(a.work_order, s.source_type) AS reference,
                       s.quantity, s.remaining
                FROM close_lot_state s JOIN material m ON m.id = s.material_id
                LEFT JOIN LATERAL (
                    SELECT MIN(x.work_order) AS work_order FROM close_batch_allocation x
                    WHERE x.close_id = s.close_id AND x.lot_key = s.lot_key AND x.pool = 'WIP'
                ) a ON TRUE
                WHERE s.close_id = :c
                ORDER BY m.code, s.pool, s.batch_no
                """).param("c", closeId).query((rs, i) -> new InventoryRow(
                rs.getString("code"), rs.getString("batch_no"), rs.getString("pool"),
                rs.getString("reference"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("remaining"))).list();
    }

    public String canonicalJson(Object value) {
        try {
            return Canonical.canonicalJson(mapper.valueToTree(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
