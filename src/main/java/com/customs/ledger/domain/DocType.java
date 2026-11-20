package com.customs.ledger.domain;

import com.customs.ledger.common.ApiException;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Source document types accepted by the intake. */
public enum DocType {
    /** Initial bonded stock. Lines are inbound, no declaration acknowledgement required. */
    OPENING_BALANCE,
    /** Import declaration for bonded material; the legal basis for inbound quantity. */
    DECLARATION,
    /** Customs receipt/acknowledgement of a declaration; gates whether the declaration enters the books. */
    DECLARATION_RECEIPT,
    /** Warehouse receiving document, used to cross-check declared quantities. */
    WAREHOUSE_RECEIPT,
    /** Production issue (material leaves the bonded store into a work order). */
    ISSUE,
    /** Outbound from the zone, either finished goods or direct material re-export. */
    OUTBOUND,
    /** Physical stocktake result, used as a variance check. */
    STOCKTAKE,
    /** Manual correction of material quantity, always linked to a reason. */
    ADJUSTMENT,
    /** Void of a previously received document version. */
    VOID;

    public static DocType from(String value) {
        if (value == null) {
            throw ApiException.badRequest("INVALID_DOC_TYPE", "docType is required");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_DOC_TYPE",
                    "unsupported docType '" + value + "', expected one of " + Arrays.stream(values())
                            .map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    public boolean affectsBalance() {
        return this == OPENING_BALANCE || this == DECLARATION || this == ISSUE
                || this == OUTBOUND || this == ADJUSTMENT;
    }
}
