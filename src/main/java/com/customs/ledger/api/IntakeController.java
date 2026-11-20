package com.customs.ledger.api;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.DocLine;
import com.customs.ledger.ingest.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Source document intake. The request body is the source system's own JSON and is stored verbatim;
 * source version and business time arrive as headers so any source system can post without a wrapper.
 * A JSON envelope is also accepted when headers are inconvenient.
 */
@RestController
@RequestMapping("/api/v1/ledgers/{code}/documents")
@Tag(name = "Document intake", description = "Receive source documents, keep every version, support corrections")
public class IntakeController {

    private final IngestionService ingestion;

    public IntakeController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    public record IntakeResponse(long documentId, String docType, String sourceSystem, String externalId,
                                 int sourceVersion, OffsetDateTime businessTime, OffsetDateTime receivedAt,
                                 String payloadHash, boolean effective, boolean duplicate,
                                 Long supersedesDocumentId, Long voidOfDocumentId, String correctionReason,
                                 List<String> warnings, List<LineView> lines) {
    }

    public record LineView(int lineNo, String sourceItemCode, String sourceUnit, String sourceQuantity,
                           String batchNo, String workOrder, String material, String baseQuantity) {
    }

    public record Envelope(String docType, String sourceSystem, String externalId, Integer sourceVersion,
                           OffsetDateTime businessTime, Object payload, String idempotencyKey,
                           Long supersedesDocumentId, Long voidOfDocumentId, String correctionReason,
                           Boolean supersedePrevious) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive one document version",
            description = "Headers: X-Doc-Type (required), X-Source-System (required), X-External-Id (required), "
                    + "X-Source-Version (default 1), X-Business-Time (ISO-8601 with offset; required), "
                    + "X-Idempotency-Key, X-Correction-Reason, X-Supersedes-Document-Id, X-Void-Of-Document-Id. "
                    + "The body is stored verbatim. Re-posting the same source version is an idempotent replay.")
    public ResponseEntity<IntakeResponse> receive(@PathVariable String code,
                                                  @RequestHeader(value = "X-Doc-Type", required = false) String docType,
                                                  @RequestHeader(value = "X-Source-System", required = false) String sourceSystem,
                                                  @RequestHeader(value = "X-External-Id", required = false) String externalId,
                                                  @RequestHeader(value = "X-Source-Version", required = false) Integer sourceVersion,
                                                  @RequestHeader(value = "X-Business-Time", required = false) String businessTime,
                                                  @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                                  @RequestHeader(value = "X-Correction-Reason", required = false) String correctionReason,
                                                  @RequestHeader(value = "X-Supersedes-Document-Id", required = false) Long supersedesId,
                                                  @RequestHeader(value = "X-Void-Of-Document-Id", required = false) Long voidOfId,
                                                  @RequestBody String body) {
        IngestionService.IntakeCommand command = new IngestionService.IntakeCommand(docType, sourceSystem,
                externalId, sourceVersion, parseTime(businessTime), body, idempotencyKey, supersedesId, voidOfId,
                correctionReason, true);
        IngestionService.IntakeResult result = ingestion.receive(code, command);
        return respond(result);
    }

    @PostMapping(path = "/envelope", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive one document version with metadata in a JSON envelope",
            description = "Convenience form for sources that cannot set headers. The nested payload is still "
                    + "stored verbatim.")
    public ResponseEntity<IntakeResponse> receiveEnvelope(@PathVariable String code,
                                                          @RequestBody Envelope envelope) {
        String payloadJson;
        try {
            payloadJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(envelope.payload());
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_PAYLOAD", "payload is not serialisable");
        }
        IngestionService.IntakeCommand command = new IngestionService.IntakeCommand(envelope.docType(),
                envelope.sourceSystem(), envelope.externalId(), envelope.sourceVersion(), envelope.businessTime(),
                payloadJson, envelope.idempotencyKey(), envelope.supersedesDocumentId(),
                envelope.voidOfDocumentId(), envelope.correctionReason(),
                envelope.supersedePrevious() == null || envelope.supersedePrevious());
        return respond(ingestion.receive(code, command));
    }

    private ResponseEntity<IntakeResponse> respond(IngestionService.IntakeResult result) {
        Doc doc = result.doc();
        IntakeResponse response = new IntakeResponse(doc.id(), doc.type().name(), doc.sourceSystem(),
                doc.externalId(), doc.sourceVersion(), OffsetDateTime.ofInstant(doc.businessTime(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(doc.receivedAt(), java.time.ZoneOffset.UTC), doc.payloadHash(),
                doc.effective(), result.duplicate(), doc.supersedesId(), doc.voidOfId(), doc.correctionReason(),
                result.warnings(), result.lines().stream().map(this::line).toList());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    private LineView line(DocLine line) {
        return new LineView(line.lineNo(), line.sourceItemCode(), line.sourceUnit(),
                line.sourceQuantity() == null ? null : line.sourceQuantity().toPlainString(),
                line.batchNo(), line.workOrder(), null,
                line.baseQuantity() == null ? null : line.baseQuantity().toPlainString());
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Read a stored document version verbatim")
    public DocView get(@PathVariable String code, @PathVariable long documentId) {
        Doc doc = ingestion.document(code, documentId);
        return new DocView(doc.id(), doc.type().name(), doc.sourceSystem(), doc.externalId(), doc.sourceVersion(),
                OffsetDateTime.ofInstant(doc.businessTime(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(doc.receivedAt(), java.time.ZoneOffset.UTC), doc.payloadHash(),
                doc.supersedesId(), doc.voidOfId(), doc.correctionReason(), doc.effective(), doc.payloadJson(),
                ingestion.lines(doc.id()).stream().map(this::line).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "Every received version of one source document, oldest first")
    public List<DocView> history(@PathVariable String code, @RequestParam String sourceSystem,
                                 @RequestParam String docType, @RequestParam String externalId) {
        return ingestion.history(code, sourceSystem, docType, externalId).stream()
                .map(d -> new DocView(d.id(), d.type().name(), d.sourceSystem(), d.externalId(),
                        d.sourceVersion(), OffsetDateTime.ofInstant(d.businessTime(), java.time.ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(d.receivedAt(), java.time.ZoneOffset.UTC), d.payloadHash(),
                        d.supersedesId(), d.voidOfId(), d.correctionReason(), d.effective(), d.payloadJson(),
                        ingestion.lines(d.id()).stream().map(this::line).toList()))
                .toList();
    }

    public record DocView(long id, String docType, String sourceSystem, String externalId, int sourceVersion,
                          OffsetDateTime businessTime, OffsetDateTime receivedAt, String payloadHash,
                          Long supersedesDocumentId, Long voidOfDocumentId, String correctionReason,
                          boolean effective, String payload, List<LineView> lines) {
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("MISSING_BUSINESS_TIME", "X-Business-Time header is required");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_BUSINESS_TIME",
                    "X-Business-Time must be ISO-8601 with an offset, e.g. 2026-03-31T10:00:00+08:00");
        }
    }
}
