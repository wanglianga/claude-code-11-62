package com.customs.ledger.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Immutable received document version. */
public record Doc(long id, DocType type, String sourceSystem, String externalId, int sourceVersion,
                  Instant businessTime, Instant receivedAt, String payloadJson, String payloadHash,
                  Long supersedesId, Long voidOfId, String correctionReason, boolean effective,
                  JsonNode payload) {

    public static Doc withPayload(Doc doc, JsonNode payload) {
        return new Doc(doc.id(), doc.type(), doc.sourceSystem(), doc.externalId(), doc.sourceVersion(),
                doc.businessTime(), doc.receivedAt(), doc.payloadJson(), doc.payloadHash(),
                doc.supersedesId(), doc.voidOfId(), doc.correctionReason(), doc.effective(), payload);
    }

    public String key() {
        return sourceSystem + '|' + type + '|' + externalId;
    }
}
