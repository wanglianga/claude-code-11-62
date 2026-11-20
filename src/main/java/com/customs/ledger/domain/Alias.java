package com.customs.ledger.domain;

import java.time.LocalDate;

/** Effective-dated source item code to canonical material mapping. */
public record Alias(long id, String sourceSystem, String sourceItemCode, long materialId,
                    LocalDate validFrom, LocalDate validTo) {

    public boolean effectiveOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
