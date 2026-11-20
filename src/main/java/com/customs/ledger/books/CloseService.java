package com.customs.ledger.books;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.LedgerCalculator;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.calc.SnapshotFactory;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Canonical;
import com.customs.ledger.common.Periods;
import com.customs.ledger.diff.DiscrepancyRepository;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.DocType;
import com.customs.ledger.domain.DocumentRepository;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.domain.Material;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monthly closing. Each close runs in a single transaction that first takes a PostgreSQL
 * transaction-scoped advisory lock on (ledger, period), so concurrent closes for the same period are
 * serialized. The close freezes its inputs into a snapshot and writes an immutable result version.
 * A newer version supersedes an older one logically; the older snapshot and result rows are never
 * modified, so a previously reported balance can always be reproduced.
 */
@Service
public class CloseService {

    private static final Logger log = LoggerFactory.getLogger(CloseService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LedgerRepository ledgers;
    private final DocumentRepository documents;
    private final CloseRepository closes;
    private final DiscrepancyRepository discrepancies;
    private final LedgerCalculator calculator;
    private final ObjectMapper mapper;
    private final com.customs.ledger.config.LedgerProperties properties;

    public CloseService(JdbcClient jdbc, TransactionTemplate tx, LedgerRepository ledgers,
                        DocumentRepository documents, CloseRepository closes,
                        DiscrepancyRepository discrepancies, LedgerCalculator calculator, ObjectMapper mapper,
                        com.customs.ledger.config.LedgerProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledgers = ledgers;
        this.documents = documents;
        this.closes = closes;
        this.discrepancies = discrepancies;
        this.calculator = calculator;
        this.mapper = mapper;
        this.properties = properties;
    }

    public record CloseCommand(String mode, String closedBy, String note, boolean allowOverwrite,
                               boolean waitForLock) {
    }

    public record CloseResult(Close close, int openDiscrepancies, List<String> warnings, boolean overwritten,
                              boolean reused) {
    }

    public CloseResult close(String ledgerCode, String periodText, CloseCommand command) {
        Ledger ledger = ledgers.requireLedger(ledgerCode);
        YearMonth period = Periods.parse(periodText);
        String mode = resolveMode(command.mode());
        try {
            return tx.execute(status -> doClose(ledger, period, mode, command));
        } catch (RuntimeException e) {
            log.warn("close failed ledger={} period={}: {}", ledgerCode, periodText, e.getMessage());
            throw e;
        }
    }

    private String resolveMode(String requested) {
        String mode = requested == null || requested.isBlank()
                ? properties.getMaterialization().getDefaultMode() : requested.trim().toUpperCase();
        if (mode.equals("AUTO")) {
            return "AUTO";
        }
        if (!mode.equals("MATERIALIZED") && !mode.equals("REBUILD")) {
            throw ApiException.badRequest("INVALID_MODE", "mode must be MATERIALIZED, REBUILD or AUTO");
        }
        return mode;
    }

    /**
     * Resolves AUTO after the document set is known: large months (whose audit queries are expensive to
     * rebuild) are materialized; small months keep the lighter rebuild mode. This is the volume-based
     * trade-off between storage/revision cost and trace-query latency.
     */
    private String decideMode(String requested, int documentCount) {
        if (!requested.equals("AUTO")) {
            return requested;
        }
        int threshold = properties.getMaterialization().getAutoThresholdDocuments();
        return documentCount > threshold ? "REBUILD" : "MATERIALIZED";
    }

    private void acquireLock(long ledgerId, YearMonth period, boolean wait) {
        long lockKey = Math.abs((ledgerId * 1_000_003L) ^ period.toString().hashCode());
        Boolean got;
        if (wait) {
            // Blocking variant returns void; wrap it so the driver still sees a single row/column.
            got = jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) locked")
                    .param("k", lockKey).query(Integer.class).optional().map(v -> true).orElse(true);
        } else {
            got = jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", lockKey)
                    .query(Boolean.class).single();
        }
        if (!Boolean.TRUE.equals(got)) {
            throw ApiException.conflict("CLOSE_IN_PROGRESS",
                    "another close for period " + Periods.format(period) + " is in progress; retry shortly");
        }
    }

    private CloseResult doClose(Ledger ledger, YearMonth period, String mode, CloseCommand command) {
        String periodKey = Periods.format(period);
        String closedBy = command.closedBy() == null || command.closedBy().isBlank() ? "system" : command.closedBy();

        acquireLock(ledger.id(), period, command.waitForLock());

        Close previous = closes.latest(ledger.id(), periodKey).orElse(null);
        Close priorPeriodClose = closes.latestClosed(ledger.id(), Periods.format(period.minusMonths(1))).orElse(null);

        List<Doc> effective = scopeDocuments(ledger, period);
        Map<Long, String> payloadJson = new HashMap<>();
        for (Doc doc : effective) {
            payloadJson.put(doc.id(), doc.payloadJson());
        }

        // A re-close must keep the earlier version's mode: switching MATERIALIZED to REBUILD (or back)
        // would silently change what the reported balance can be audited against.
        String effectiveMode = previous != null ? previous.mode() : decideMode(mode, effective.size());

        List<Snapshot.SnapshotLot> openingLots = new ArrayList<>();
        List<Snapshot.PriorTotal> priorTotals = new ArrayList<>();
        if (priorPeriodClose != null) {
            for (Computation.LotState lot : closes.lotStates(priorPeriodClose.id())) {
                // Only what is still on hand at the prior close opens the next period. Using the
                // lot's original quantity here would count already-consumed material twice.
                openingLots.add(new Snapshot.SnapshotLot(lot.lotKey(), lot.materialCode(), lot.batchNo(),
                        lot.pool(), lot.remaining(), lot.remaining(), lot.businessDate(), lot.sourceDocumentId(),
                        lot.sourceType()));
            }
            priorTotals.addAll(closes.priorTotals(priorPeriodClose.id()).values());
        }

        Snapshot snapshot = SnapshotFactory.build(new SnapshotFactory.Inputs(
                ledger, period, effectiveMode, LedgerCalculator.ALGORITHM, effective, payloadJson,
                ledgers.allAliases(ledger.id()), ledgers.allConversions(ledger.id()),
                ledgers.allRelations(ledger.id()), ledgers.materials(ledger.id()),
                priorPeriodClose == null ? null : new SnapshotFactory.PriorState(priorPeriodClose.id(),
                        priorPeriodClose.version(), priorPeriodClose.resultHash(), priorPeriodClose.period(),
                        openingLots, priorTotals)));

        Computation computation = calculator.compute(snapshot);
        if (computation.fatal()) {
            throw ApiException.unprocessable("CONSERVATION_VIOLATION",
                    "quantity conservation check failed: " + String.join("; ", computation.fatalErrors()));
        }

        Map<String, Long> materialIds = new HashMap<>();
        for (Material material : ledgers.materials(ledger.id())) {
            materialIds.put(material.code(), material.id());
        }

        String inputHash = inputHash(snapshot);

        if (previous != null && previous.status().equals("CLOSED") && previous.inputHash().equals(inputHash)
                && !hasNewDocumentsSince(previous, effective)) {
            return new CloseResult(previous, openCount(ledger.id(), periodKey), computation.warnings(), false, true);
        }
        if (previous != null && previous.status().equals("CLOSED") && !command.allowOverwrite()) {
            throw ApiException.conflict("CLOSE_INPUTS_CHANGED",
                    "period " + periodKey + " was already closed as version " + previous.version()
                            + " and its inputs have changed; re-close with allowOverwrite=true to append an "
                            + "adjustment version that references the new source versions");
        }

        List<Computation.ChangeDraft> changes = describeChanges(previous, effective);
        int version = closes.nextVersion(ledger.id(), periodKey);
        String resultHash = resultHash(computation);

        long closeId = closes.insert(ledger.id(), periodKey, version, effectiveMode, snapshot.algorithmVersion(),
                priorPeriodClose == null ? null : priorPeriodClose.id(),
                previous == null ? null : previous.id(),
                inputHash, resultHash, closes.snapshotJson(snapshot), snapshot.documents().size(),
                closedBy, command.note());

        closes.insertResults(closeId, materialIds, computation);
        closes.insertLotStates(closeId, materialIds, computation.lots());
        closes.insertMaterialAdjustments(closeId, materialIds, computation.adjustments());
        if (effectiveMode.equals("MATERIALIZED")) {
            closes.insertAllocations(closeId, ledger.id(), materialIds, computation.allocations());
        }
        closes.insertDocuments(closeId, snapshot.documents());
        closes.insertChanges(closeId, changes);

        if (previous != null) {
            closes.markSuperseded(previous.id());
        }

        List<DiscrepancyRepository.Draft> drafts = new ArrayList<>();
        for (Computation.DiscrepancyDraft d : computation.discrepancies()) {
            drafts.add(new DiscrepancyRepository.Draft(d.kind(), d.materialCode(), d.sourceSystem(),
                    d.sourceItemCode(), d.expected(), d.actual(), d.message(), json(d.detail())));
        }
        addCoverageDiscrepancies(ledger, period, priorPeriodClose, drafts);
        discrepancies.insertAll(ledger.id(), periodKey, closeId, materialIds, drafts);
        supersedeStaleDiscrepancies(ledger.id(), periodKey, closeId);

        int open = openCount(ledger.id(), periodKey);
        log.info("closed ledger={} period={} version={} mode={} docs={} allocations={} resultHash={}",
                ledger.code(), periodKey, version, effectiveMode, snapshot.documents().size(),
                computation.allocations().size(), resultHash.substring(0, 12));

        return new CloseResult(closes.requireById(closeId), open, computation.warnings(), previous != null, false);
    }

    /** Flags missing prior-period closes so a month is not presented as self-contained when it is not. */
    private void addCoverageDiscrepancies(Ledger ledger, YearMonth period, Close priorClose,
                                          List<DiscrepancyRepository.Draft> drafts) {
        if (priorClose != null) {
            return;
        }
        Instant from = Instant.EPOCH;
        Instant to = period.atDay(1).atStartOfDay(ZoneId.of(ledger.zone())).toInstant();
        boolean hasEarlier = documents.effectiveDocuments(ledger.id()).stream()
                .anyMatch(d -> d.businessTime().isBefore(to) && d.type().affectsBalance());
        if (hasEarlier) {
            drafts.add(new DiscrepancyRepository.Draft("PRIOR_PERIOD_NOT_CLOSED", null, "ledger", null,
                    null, null, "documents dated before " + Periods.format(period)
                    + " exist but the prior period was never closed; opening balance comes from "
                    + "OPENING_BALANCE documents only", json(Map.of("from", from.toString(), "to", to.toString()))));
        }
    }

    /**
     * Documents in scope for the period: everything whose business time falls in the period, plus
     * evidence documents (customs acknowledgement / warehouse receipt) that reference an in-scope
     * declaration even when the acknowledgement itself arrived in a later month.
     */
    private List<Doc> scopeDocuments(Ledger ledger, YearMonth period) {
        ZoneId zone = ZoneId.of(ledger.zone());
        Instant from = period.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        List<Doc> all = documents.effectiveDocuments(ledger.id());
        Set<String> inScopeDeclarations = new HashSet<>();
        for (Doc doc : all) {
            if (doc.type() == DocType.DECLARATION
                    && !doc.businessTime().isBefore(from) && doc.businessTime().isBefore(to)) {
                inScopeDeclarations.add(doc.sourceSystem() + '|' + doc.externalId());
            }
        }
        List<Doc> scoped = new ArrayList<>();
        for (Doc doc : all) {
            boolean inPeriod = !doc.businessTime().isBefore(from) && doc.businessTime().isBefore(to);
            boolean evidenceOfScope = false;
            if (!inPeriod && (doc.type() == DocType.DECLARATION_RECEIPT || doc.type() == DocType.WAREHOUSE_RECEIPT)) {
                String[] reference = referenceOf(doc);
                if (reference != null && reference[1] != null) {
                    evidenceOfScope = reference[0] != null
                            ? inScopeDeclarations.contains(reference[0] + '|' + reference[1])
                            : inScopeDeclarations.stream().anyMatch(k -> k.endsWith("|" + reference[1]));
                }
            }
            if (inPeriod || evidenceOfScope) {
                scoped.add(doc);
            }
        }
        return scoped;
    }

    /** @return {sourceSystem-or-null, externalId} referenced by an evidence document, or null */
    private String[] referenceOf(Doc doc) {
        try {
            var canonical = com.customs.ledger.ingest.PayloadExtractor.canonicalise(
                    mapper.readTree(doc.payloadJson()), doc.type().name());
            if (canonical.referenceExternalId() == null) {
                return null;
            }
            return new String[]{canonical.referenceSourceSystem(), canonical.referenceExternalId()};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A close is only reusable when the current document set matches what it froze. Documents that
     * arrived after the close (even with an unchanged business period) require a new version so the
     * late arrival or correction is recorded against a cited source version.
     */
    private boolean hasNewDocumentsSince(Close previous, List<Doc> current) {
        Map<Long, Integer> frozen = closes.documentVersions(previous.id());
        Set<Long> currentIds = new HashSet<>();
        for (Doc doc : current) {
            currentIds.add(doc.id());
            Integer version = frozen.get(doc.id());
            if (version == null || version != doc.sourceVersion()) {
                return true;
            }
        }
        return !frozen.keySet().equals(currentIds);
    }

    /** Closes the pending discrepancies raised by earlier, superseded versions of this period. */
    private void supersedeStaleDiscrepancies(long ledgerId, String period, long currentCloseId) {
        jdbc.sql("""
                UPDATE discrepancy SET status = 'RESOLVED', resolution = 'SUPERSEDED_BY_RECLOSE',
                       resolution_note = 'resolved by close version id ' || :c, resolved_by = 'system',
                       resolved_at = now(), updated_at = now()
                WHERE ledger_id = :l AND period = :p AND status = 'OPEN' AND close_id <> :c
                """).param("c", currentCloseId).param("l", ledgerId).param("p", period).update();
    }

    private int openCount(long ledgerId, String period) {
        return jdbc.sql("SELECT count(*) FROM discrepancy WHERE ledger_id = :l AND period = :p AND status = 'OPEN'")
                .param("l", ledgerId).param("p", period).query(Integer.class).single();
    }

    private List<Computation.ChangeDraft> describeChanges(Close previous, List<Doc> effective) {
        List<Computation.ChangeDraft> changes = new ArrayList<>();
        if (previous == null) {
            return changes;
        }
        Map<Long, Integer> priorVersions = closes.documentVersions(previous.id());
        for (Doc doc : effective) {
            if (doc.supersedesId() != null && priorVersions.containsKey(doc.supersedesId())) {
                changes.add(new Computation.ChangeDraft("SUPERSEDED", doc.id(), doc.supersedesId(),
                        doc.type().name(), doc.externalId(), doc.businessTime(),
                        orderedMap("sourceVersion", doc.sourceVersion(), "reason",
                                doc.correctionReason() == null ? "" : doc.correctionReason())));
            }
            if (doc.voidOfId() != null && priorVersions.containsKey(doc.voidOfId())) {
                changes.add(new Computation.ChangeDraft("VOIDED", doc.id(), doc.voidOfId(),
                        doc.type().name(), doc.externalId(), doc.businessTime(),
                        orderedMap("sourceVersion", doc.sourceVersion())));
            }
            if (!priorVersions.containsKey(doc.id()) && doc.supersedesId() == null && doc.voidOfId() == null) {
                changes.add(new Computation.ChangeDraft("LATE_ARRIVAL", doc.id(), null, doc.type().name(),
                        doc.externalId(), doc.businessTime(), orderedMap("sourceVersion", doc.sourceVersion())));
            }
        }
        return changes;
    }

    private Map<String, Object> orderedMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private Map<String, Object> orderedMap(String k1, Object v1) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        return map;
    }

    private String inputHash(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(snapshot.mode()).append('|').append(snapshot.algorithmVersion()).append('\n');
        for (Snapshot.SnapshotDoc doc : snapshot.documents()) {
            sb.append(doc.id()).append('|').append(doc.sourceVersion()).append('|')
                    .append(doc.payloadHash()).append('|').append(doc.businessTime()).append('\n');
        }
        for (Snapshot.SnapshotLot lot : snapshot.openingLots()) {
            sb.append("L|").append(lot.lotKey()).append('|').append(lot.remaining()).append('\n');
        }
        for (Snapshot.MappingRow row : snapshot.mappingUsage().aliases()) {
            sb.append("A|").append(row.id()).append('|').append(row.validOn()).append('\n');
        }
        for (Snapshot.MappingRow row : snapshot.mappingUsage().conversions()) {
            sb.append("C|").append(row.id()).append('|').append(row.factor()).append('|')
                    .append(row.validOn()).append('\n');
        }
        for (Snapshot.MappingRow row : snapshot.mappingUsage().relations()) {
            sb.append("P|").append(row.id()).append('|').append(row.factor()).append('|')
                    .append(row.validOn()).append('\n');
        }
        return Canonical.sha256(sb.toString());
    }

    public static String resultHash(Computation computation) {
        StringBuilder sb = new StringBuilder();
        for (Computation.MaterialResult r : computation.materials()) {
            sb.append(r.materialCode()).append('|').append(r.opening()).append('|').append(r.inbound())
                    .append('|').append(r.consumed()).append('|').append(r.outbound()).append('|')
                    .append(r.outboundContent()).append('|').append(r.adjust()).append('|')
                    .append(r.closing()).append('\n');
        }
        sb.append("--allocations--\n");
        for (Computation.AllocationRow a : computation.allocations()) {
            sb.append(a.materialCode()).append('|').append(a.lotKey()).append('|').append(a.movementType())
                    .append('|').append(a.documentId()).append('|').append(a.quantity()).append('\n');
        }
        return Canonical.sha256(sb.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
