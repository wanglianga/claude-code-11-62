package com.customs.ledger.books;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.LedgerCalculator;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.diff.DiscrepancyRepository;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the ledger. A MATERIALIZED close serves its stored per-batch allocation relation
 * directly (fast audit queries). A REBUILD close stores only the frozen snapshot and the algorithm
 * version, and the allocation relation is recomputed deterministically on each query; this can be
 * verified against the stored result hash.
 */
@Service
public class CloseQueryService {

    private final LedgerRepository ledgers;
    private final CloseRepository closes;
    private final DiscrepancyRepository discrepancies;
    private final LedgerCalculator calculator;

    public CloseQueryService(LedgerRepository ledgers, CloseRepository closes,
                             DiscrepancyRepository discrepancies, LedgerCalculator calculator) {
        this.ledgers = ledgers;
        this.closes = closes;
        this.discrepancies = discrepancies;
        this.calculator = calculator;
    }

    public record ViewOptions(boolean includeAllocations, boolean verifyHash) {
    }

    public CloseView view(String ledgerCode, long closeId, ViewOptions options) {
        Ledger ledger = ledgers.requireLedger(ledgerCode);
        Close close = closes.requireById(closeId);
        if (!close.ledgerCode().equals(ledger.code())) {
            throw ApiException.notFound("CLOSE_NOT_FOUND", "close " + closeId + " does not belong to " + ledgerCode);
        }

        boolean materialized = close.mode().equals("MATERIALIZED");
        Snapshot snapshot = closes.snapshot(close.id());
        Computation computed;
        if (materialized && !options.verifyHash()) {
            computed = null;
        } else {
            computed = calculator.compute(snapshot);
        }

        List<CloseView.MaterialBalance> balances;
        if (materialized) {
            balances = closes.materialBalances(close.id());
        } else {
            balances = toBalances(computed);
        }

        List<CloseView.BatchAllocation> allocations = List.of();
        if (options.includeAllocations()) {
            if (materialized) {
                allocations = closes.allocations(close.id(), null, null);
            } else {
                allocations = toAllocations(computed);
            }
        }

        List<CloseView.DiscrepancyView> discrepancyViews = discrepancies.query(ledger.id(), close.period(), null, null, 500)
                .stream()
                .filter(r -> r.closeId() != null && r.closeId() == close.id())
                .map(r -> new CloseView.DiscrepancyView(r.id(), r.kind(), r.material(), r.sourceSystem(),
                        r.sourceItemCode(), r.expected(), r.actual(), r.message(), r.status(), r.resolution(),
                        r.resolutionNote(), r.adjustmentDocumentId(), r.documentId()))
                .toList();

        List<String> warnings = new ArrayList<>();
        if (!materialized) {
            warnings.add("REBUILD mode: balances and allocations were recomputed from the frozen snapshot; "
                    + "no per-batch relation is materialized for this close");
        }
        if (options.verifyHash() && computed != null) {
            String hash = CloseService.resultHash(computed);
            if (!hash.equals(close.resultHash())) {
                throw ApiException.conflict("RESULT_HASH_MISMATCH",
                        "recomputed result hash " + hash + " differs from stored " + close.resultHash()
                                + "; the algorithm version or snapshot no longer reproduces the reported balance");
            }
            warnings.add("verified: recomputed result hash equals the stored hash");
        }

        return new CloseView(close, materialized, balances, allocations, discrepancyViews,
                closes.adjustments(close.id()), closes.changes(close.id()), warnings);
    }

    public List<CloseView.BatchAllocation> allocations(String ledgerCode, long closeId, String material, String lotKey) {
        Ledger ledger = ledgers.requireLedger(ledgerCode);
        Close close = closes.requireById(closeId);
        if (!close.ledgerCode().equals(ledger.code())) {
            throw ApiException.notFound("CLOSE_NOT_FOUND", "close " + closeId + " does not belong to " + ledgerCode);
        }
        if (close.mode().equals("MATERIALIZED")) {
            return closes.allocations(close.id(), material, lotKey);
        }
        Computation computed = calculator.compute(closes.snapshot(close.id()));
        return toAllocations(computed).stream()
                .filter(a -> material == null || a.material().equals(material))
                .filter(a -> lotKey == null || a.lotKey().equals(lotKey))
                .toList();
    }

    private List<CloseView.MaterialBalance> toBalances(Computation computation) {
        return computation.materials().stream()
                .map(r -> new CloseView.MaterialBalance(r.materialCode(), null, null, r.opening(), r.inbound(),
                        r.consumed(), r.outbound(), r.outboundContent(), r.adjust(), r.closing(), r.wipOpening(),
                        r.wipClosing(), r.cumulativeConsumed(), r.cumulativeOutboundContent(), r.allocationCount()))
                .toList();
    }

    private List<CloseView.BatchAllocation> toAllocations(Computation computation) {
        return computation.allocations().stream()
                .map(a -> new CloseView.BatchAllocation(a.materialCode(), a.lotKey(), a.batchNo(), a.pool(),
                        a.movementType(), a.documentId(), a.lineNo(), a.workOrder(), a.quantity(),
                        a.allocationSeq()))
                .toList();
    }
}
