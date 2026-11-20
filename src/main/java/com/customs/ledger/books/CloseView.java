package com.customs.ledger.books;

import java.math.BigDecimal;
import java.util.List;

public record CloseView(
        Close close,
        boolean materialized,
        List<MaterialBalance> materials,
        List<BatchAllocation> allocations,
        List<DiscrepancyView> discrepancies,
        List<AdjustmentView> adjustments,
        List<ChangeView> changes,
        List<String> warnings) {

    public record MaterialBalance(String material, String name, String unit,
                                  BigDecimal opening, BigDecimal inbound, BigDecimal consumed,
                                  BigDecimal outbound, BigDecimal outboundContent, BigDecimal adjust,
                                  BigDecimal closing, BigDecimal wipOpening, BigDecimal wipClosing,
                                  BigDecimal cumulativeConsumed, BigDecimal cumulativeOutboundContent,
                                  int allocationCount) {
    }

    public record BatchAllocation(String material, String lotKey, String batchNo, String pool,
                                  String movementType, Long documentId, Integer lineNo, String workOrder,
                                  BigDecimal quantity, int allocationSeq) {
    }

    public record DiscrepancyView(long id, String kind, String material, String sourceSystem,
                                  String sourceItemCode, BigDecimal expected, BigDecimal actual,
                                  String message, String status, String resolution, String resolutionNote,
                                  Long adjustmentDocumentId, Long documentId) {
    }

    public record AdjustmentView(String material, BigDecimal delta, String reason) {
    }

    public record ChangeView(String kind, Long documentId, Long priorDocumentId, String docType,
                             String externalId, String businessTime, String detail) {
    }
}
