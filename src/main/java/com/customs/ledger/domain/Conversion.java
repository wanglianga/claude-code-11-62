package com.customs.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Effective-dated unit -> base unit factor for a material. */
public record Conversion(long id, long materialId, String fromUnit, BigDecimal factor,
                         LocalDate validFrom, LocalDate validTo) {

    public boolean effectiveOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
