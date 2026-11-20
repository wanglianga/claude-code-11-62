package com.customs.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Effective-dated processing (bill of material) relation: one output consumes qtyPerOutput of one input. */
public record ProcessingRelation(long id, long outputMaterialId, long inputMaterialId,
                                 BigDecimal qtyPerOutput, LocalDate validFrom, LocalDate validTo) {

    public boolean effectiveOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
