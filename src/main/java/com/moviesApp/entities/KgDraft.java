package com.moviesApp.entities;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One stage of an unpublished knowledge-base build (source, vocabulary, metadata, taxonomy, ontology,
 * population). The payload is the stage's JSON as the user last edited it; the published graph lives
 * in Neo4j and is never read from here.
 */
@Entity
@Table(name = "kg_draft", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"label", "stage"})
})
public class KgDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "stage", nullable = false, length = 30)
    private String stage;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** DRAFT (editable), APPROVED (locked in for later stages) or STALE (an earlier stage changed). */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public KgDraft() {}

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
