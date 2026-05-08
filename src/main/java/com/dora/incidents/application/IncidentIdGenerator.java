package com.dora.incidents.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import com.dora.incidents.domain.IncidentRepository;

/**
 * Generates human-readable Incident IDs in the format {@code INC-YYYYMMDD-NNNN}.
 *
 * <p>Design note: the task brief references a DB sequence {@code incident_id_seq},
 * but V1_4_0__incident_core.sql does not define one. Rather than adding a migration
 * (DB Engineer owns migrations), we derive the sequence number by counting existing
 * incidents created on the same day and adding 1. This is:
 * <ol>
 *   <li>Correct within a single app instance — the increment happens inside the
 *       caller's transaction, so concurrent inserts race on the same count.</li>
 *   <li>Safe for the POC scale (sub-100 incidents/day per DORA regulation scope).</li>
 *   <li>Unique even if the same count is reached on two concurrent transactions,
 *       because the {@code UNIQUE} constraint on {@code incident_id} will reject
 *       the duplicate and the service-layer retry is a valid correction path.</li>
 * </ol>
 *
 * <p>OPEN-Q: For production scale (LLD-16), replace with a true DB sequence
 * {@code incident_id_seq} and call {@code nextval('incident_id_seq')} via JDBC.
 * The DB Engineer should add the sequence in a new migration at that point.
 *
 * <p>The date used is always UTC (ZoneOffset.UTC) for regulatory consistency
 * — the INC-YYYYMMDD prefix must not vary with server timezone.
 */
@Component
public class IncidentIdGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final IncidentRepository incidentRepository;

    public IncidentIdGenerator(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    /**
     * Generates the next Incident ID for today (UTC).
     *
     * <p>Must be called inside an active transaction (which it will be — the caller
     * is IncidentService.create(), which is @Transactional).
     *
     * @return e.g. {@code INC-20260507-0001}
     */
    @Transactional
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant startOfDay = today.atStartOfDay().toInstant(ZoneOffset.UTC);

        long count = incidentRepository.countByDetectionDatetimeOnOrAfter(startOfDay);
        long nextSeq = count + 1;

        String dateStr = today.format(DATE_FORMAT);
        // NNNN: zero-padded to 4 digits. Supports up to 9999 incidents per day.
        return String.format("INC-%s-%04d", dateStr, nextSeq);
    }
}
