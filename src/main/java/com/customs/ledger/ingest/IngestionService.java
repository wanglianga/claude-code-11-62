package com.customs.ledger.ingest;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Canonical;
import com.customs.ledger.common.Periods;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.DocLine;
import com.customs.ledger.domain.DocType;
import com.customs.ledger.domain.DocumentRepository;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.domain.Material;
import com.customs.ledger.rating.MappingResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Intake of source documents. Every received version is stored verbatim together with its source
 * version, business time and payload hash. Re-sending the same version is an idempotent replay;
 * a new version supersedes the previous one logically but never overwrites it.
 */
@Service
public class IngestionService {

    private final LedgerRepository ledgers;
    private final DocumentRepository documents;
    private final MappingResolver resolver;
    private final ObjectMapper mapper;

    public IngestionService(LedgerRepository ledgers, DocumentRepository documents, MappingResolver resolver,
                            ObjectMapper mapper) {
        this.ledgers = ledgers;
        this.documents = documents;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    public record IntakeCommand(String docType, String sourceSystem, String externalId, Integer sourceVersion,
                                OffsetDateTime businessTime, String rawBody, String idempotencyKey,
                                Long supersedesDocumentId, Long voidOfDocumentId, String correctionReason,
                                boolean supersedePrevious) {
    }

    public record IntakeResult(Doc doc, boolean duplicate, List<String> warnings, List<DocLine> lines) {
    }

    @Transactional
    public IntakeResult receive(String ledgerCode, IntakeCommand command) {
        var ledger = ledgers.requireLedger(ledgerCode);
        DocType type = DocType.from(command.docType());
        if (command.sourceSystem() == null || command.sourceSystem().isBlank()) {
            throw ApiException.badRequest("INVALID_SOURCE_SYSTEM", "sourceSystem is required");
        }
        if (command.externalId() == null || command.externalId().isBlank()) {
            throw ApiException.badRequest("INVALID_EXTERNAL_ID", "externalId is required");
        }
        if (command.businessTime() == null) {
            throw ApiException.badRequest("INVALID_BUSINESS_TIME", "businessTime is required (ISO-8601 with offset)");
        }
        if (type == DocType.VOID && command.voidOfDocumentId() == null && command.supersedesDocumentId() == null) {
            throw ApiException.badRequest("VOID_TARGET_REQUIRED",
                    "a VOID document must reference the voided document via voidOfDocumentId or supersedesDocumentId");
        }

        JsonNode raw = parse(command.rawBody());
        PayloadExtractor.Canonical canonical = type == DocType.VOID
                ? new PayloadExtractor.Canonical(null, null, List.of())
                : PayloadExtractor.canonicalise(raw, type.name());

        // Re-sent identical version: return the stored copy, never create a second row.
        Optional<Doc> existing = documents.findVersion(ledger.id(), command.sourceSystem(), type,
                command.externalId(), command.sourceVersion() == null ? 1 : command.sourceVersion());
        if (existing.isPresent()) {
            Doc doc = existing.get();
            return new IntakeResult(doc, true, List.of(), documents.lines(doc.id()));
        }
        if (command.idempotencyKey() != null) {
            Optional<Doc> prior = documents.findByIdempotencyKey(ledger.id(), command.idempotencyKey());
            if (prior.isPresent()) {
                Doc doc = prior.get();
                return new IntakeResult(doc, true, List.of(), documents.lines(doc.id()));
            }
        }

        ZoneId zone = ZoneId.of(ledger.zone());
        LocalDate businessDate = Periods.businessDate(command.businessTime(), zone);

        int version = command.sourceVersion() == null
                ? documents.latestVersion(ledger.id(), command.sourceSystem(), type, command.externalId())
                        .map(d -> d.sourceVersion() + 1).orElse(1)
                : command.sourceVersion();

        Long supersedesId = command.supersedesDocumentId();
        if (supersedesId == null) {
            supersedesId = documents.latestVersion(ledger.id(), command.sourceSystem(), type, command.externalId())
                    .filter(d -> d.sourceVersion() < version && d.effective())
                    .map(Doc::id).orElse(null);
        }
        Long voidOfId = command.voidOfDocumentId();
        if (type == DocType.VOID && voidOfId == null) {
            voidOfId = supersedesId;
        }
        if (voidOfId != null) {
            Doc target = documents.requireById(voidOfId);
            if (!target.effective() && type != DocType.VOID) {
                throw ApiException.conflict("ALREADY_VOIDED",
                        "document " + voidOfId + " is already voided; re-post a new version instead");
            }
            if (target.type() == DocType.VOID) {
                throw ApiException.badRequest("CANNOT_VOID_A_VOID", "a void document cannot itself be voided");
            }
        }

        String payloadText = Canonical.canonicalJson(raw);
        String hash = Canonical.sha256(payloadText);

        Doc stored = documents.insert(ledger.id(), type, command.sourceSystem(), command.externalId(), version,
                command.businessTime().toInstant(), payloadText, command.rawBody(), hash, command.idempotencyKey(),
                supersedesId, voidOfId, command.correctionReason());

        documents.supersedeEarlier(ledger.id(), command.sourceSystem(), type, command.externalId(), version);
        documents.setEffective(stored.id(), true);
        if (voidOfId != null) {
            documents.setEffective(voidOfId, false);
        }

        List<String> warnings = new ArrayList<>();
        List<DocLine> lines = new ArrayList<>();
        boolean advisoryMapping = type == DocType.DECLARATION || type == DocType.OPENING_BALANCE
                || type == DocType.ISSUE || type == DocType.OUTBOUND || type == DocType.ADJUSTMENT
                || type == DocType.STOCKTAKE || type == DocType.DECLARATION_RECEIPT || type == DocType.WAREHOUSE_RECEIPT;
        for (PayloadExtractor.CanonicalLine line : canonical.lines()) {
            DocLine mapped = advisoryMapping
                    ? mapLine(ledger.id(), line, businessDate, warnings, type)
                    : new DocLine(0, stored.id(), line.lineNo(), line.itemCode(), line.unit(), line.quantity(),
                        line.batchNo(), line.workOrder(), null, null, null, null, line.outputItemCode());
            documents.insertLine(stored.id(), mapped);
            lines.add(mapped);
        }

        Doc reread = documents.requireById(stored.id());
        return new IntakeResult(reread, false, warnings, documents.lines(stored.id()));
    }

    private DocLine mapLine(long ledgerId, PayloadExtractor.CanonicalLine line, LocalDate businessDate,
                            List<String> warnings, DocType type) {
        if (line.itemCode() == null) {
            return new DocLine(0, 0, line.lineNo(), null, line.unit(), line.quantity(), line.batchNo(),
                    line.workOrder(), null, null, null, null, line.outputItemCode());
        }
        if (line.unit() == null || line.unit().isBlank()) {
            warnings.add("line " + line.lineNo() + " has no unit; assuming the material base unit");
        }
        try {
            var alias = resolver.resolveAlias(ledgerId, line.itemCode(), businessDate);
            Material material = ledgers.requireMaterial(ledgerId, alias.materialCode());
            String unit = PayloadExtractor.normaliseUnit(line.unit());
            var conversion = resolver.resolveConversion(ledgerId, material.id(), material.code(), material.baseUnit(),
                    unit == null ? material.baseUnit() : unit, businessDate);
            var base = resolver.toBase(conversion, line.quantity());
            return new DocLine(0, 0, line.lineNo(), line.itemCode(), unit, line.quantity(), line.batchNo(),
                    line.workOrder(), material.id(), base, alias.alias().id(),
                    conversion.conversion() == null ? null : conversion.conversion().id(), line.outputItemCode());
        } catch (ApiException e) {
            warnings.add("line " + line.lineNo() + " could not be mapped at intake (" + e.getCode()
                    + "); the close will re-resolve it by business date");
            return new DocLine(0, 0, line.lineNo(), line.itemCode(), line.unit(), line.quantity(), line.batchNo(),
                    line.workOrder(), null, null, null, null, line.outputItemCode());
        }
    }

    private JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw ApiException.badRequest("EMPTY_PAYLOAD", "request body must contain the source document JSON");
        }
        try {
            return mapper.readTree(rawBody);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_JSON", "request body is not valid JSON: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- reads

    public Doc document(String ledgerCode, long id) {
        var ledger = ledgers.requireLedger(ledgerCode);
        Doc doc = documents.requireById(id);
        return DocumentRepository.withPayload(doc, mapper, doc.payloadJson());
    }

    public List<Doc> history(String ledgerCode, String sourceSystem, String docType, String externalId) {
        var ledger = ledgers.requireLedger(ledgerCode);
        return documents.versionsOf(ledger.id(), sourceSystem, DocType.from(docType), externalId).stream()
                .map(d -> DocumentRepository.withPayload(d, mapper, d.payloadJson()))
                .toList();
    }

    public List<DocLine> lines(long documentId) {
        return documents.lines(documentId);
    }
}
