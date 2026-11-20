package com.customs.ledger.calc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Output of the deterministic calculation for one period. */
public record Computation(
        List<MaterialResult> materials,
        List<AllocationRow> allocations,
        List<LotState> lots,
        List<DiscrepancyDraft> discrepancies,
        List<AdjustmentDraft> adjustments,
        List<ChangeDraft> changes,
        List<String> warnings,
        List<String> fatalErrors) {

    public boolean fatal() {
        return !fatalErrors.isEmpty();
    }

    /**
     * A material's balance. Two pools are tracked:
     * stock: opening + inbound - issued + adjust = closing stock.
     * work in process: wipOpening + issued - outbound content = closing WIP.
     */
    public record MaterialResult(String materialCode, BigDecimal opening, BigDecimal inbound, BigDecimal consumed,
                                 BigDecimal outbound, BigDecimal outboundContent, BigDecimal adjust,
                                 BigDecimal closing, BigDecimal wipOpening, BigDecimal wipClosing,
                                 BigDecimal cumulativeConsumed, BigDecimal cumulativeOutboundContent,
                                 int allocationCount) {
    }

    /**
     * @param pool      STOCK for inbound/issue/adjust allocations, WIP for outbound write-offs
     * @param lotKey    stock lot key or WIP lot key (material|WIP|batch|workOrder)
     */
    public record AllocationRow(String materialCode, String lotKey, String batchNo, String pool,
                                String movementType, Long documentId, Integer lineNo, String workOrder,
                                BigDecimal quantity, int allocationSeq) {
    }

    public record LotState(String lotKey, String materialCode, String batchNo, String pool, BigDecimal quantity,
                           BigDecimal remaining, LocalDate businessDate, Long sourceDocumentId, String sourceType) {
    }

    public record DiscrepancyDraft(String kind, String materialCode, String sourceSystem, String sourceItemCode,
                                   BigDecimal expected, BigDecimal actual, String message,
                                   Map<String, Object> detail) {
    }

    public record AdjustmentDraft(String materialCode, BigDecimal delta, String reason) {
    }

    public record ChangeDraft(String kind, Long documentId, Long priorDocumentId, String docType, String externalId,
                              Instant businessTime, Map<String, Object> detail) {
    }
}
