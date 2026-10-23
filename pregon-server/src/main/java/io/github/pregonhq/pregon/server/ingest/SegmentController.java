package io.github.pregonhq.pregon.server.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import io.github.pregonhq.pregon.server.security.ApiKeyPrincipal;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PUT /v1/segments/{segmentId}} (ADR-0024): the segment id in the URL is the idempotency
 * key, the body carries a {@code metadata} JSON part and an {@code audio} Ogg/Opus part. The
 * tenant comes from the API key, never from the request (ADR-0018).
 */
@RestController
class SegmentController {

    /** The receipt of the contract. */
    record SegmentReceipt(UUID segmentId, Instant receivedAt, boolean clockSkewSuspected) {
    }

    private final SegmentIngestion ingestion;
    private final JsonMapper json;

    SegmentController(SegmentIngestion ingestion, JsonMapper json) {
        this.ingestion = ingestion;
        this.json = json;
    }

    @PutMapping(value = "/v1/segments/{segmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SegmentReceipt> put(@PathVariable String segmentId, HttpServletRequest request) throws IOException, ServletException {
        UUID id;
        try {
            id = UUID.fromString(segmentId);
        } catch (IllegalArgumentException e) {
            throw new IngestProblems.InvalidRequest("The segment id must be a UUID.");
        }
        Part metadataPart = request.getPart("metadata");
        Part audioPart = request.getPart("audio");
        if (metadataPart == null || audioPart == null) {
            throw new IngestProblems.InvalidRequest("The body needs a 'metadata' part and an 'audio' part.");
        }
        SegmentMetadata metadata = SegmentMetadata.parse(
                new String(metadataPart.getInputStream().readAllBytes(), StandardCharsets.UTF_8), json);
        byte[] audio = audioPart.getInputStream().readAllBytes();
        if (audio.length == 0) {
            throw new IngestProblems.InvalidRequest("The audio part is empty.");
        }
        String tenantId = ((ApiKeyPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).tenantId();

        SegmentIngestion.Receipt receipt = ingestion.accept(tenantId, id, metadata, audio);

        return ResponseEntity.status(receipt.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(new SegmentReceipt(receipt.segmentId(), receipt.receivedAt(), receipt.clockSkewSuspected()));
    }
}
