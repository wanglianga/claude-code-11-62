package com.customs.ledger.domain;

import java.math.BigDecimal;

/** Extracted document line with the mapping resolved at intake time (advisory, nullable). */
public record DocLine(long id, long documentId, int lineNo, String sourceItemCode, String sourceUnit,
                      BigDecimal sourceQuantity, String batchNo, String workOrder, Long materialId,
                      BigDecimal baseQuantity, Long aliasId, Long conversionId, String outputMaterialCode) {
}
