package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.entities.KgDraft;
import com.moviesApp.repositories.KgDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OntologyDraftServiceTest {

    private final List<KgDraft> rows = new ArrayList<>();
    private OntologyDraftService service;

    @BeforeEach
    void setUp() {
        KgDraftRepository repo = mock(KgDraftRepository.class);
        when(repo.findByLabelAndStage(anyString(), anyString())).thenAnswer(inv -> rows.stream()
                .filter(d -> d.getLabel().equals(inv.getArgument(0)) && d.getStage().equals(inv.getArgument(1)))
                .findFirst());
        when(repo.findByLabel(anyString())).thenAnswer(inv -> rows.stream()
                .filter(d -> d.getLabel().equals(inv.getArgument(0))).toList());
        when(repo.save(any(KgDraft.class))).thenAnswer(inv -> {
            KgDraft d = inv.getArgument(0);
            if (!rows.contains(d)) rows.add(d);
            return d;
        });
        doAnswer(inv -> {
            rows.removeIf(d -> d.getLabel().equals(inv.getArgument(0)));
            return null;
        }).when(repo).deleteByLabel(anyString());
        service = new OntologyDraftService(repo, new ObjectMapper());
    }

    private static Map<String, Object> source() {
        return Map.of("kind", "text", "text", "Napoleon fought at Waterloo.");
    }

    private static Map<String, Object> vocab(String... terms) {
        return Map.of("terms", java.util.Arrays.stream(terms)
                .map(t -> Map.of("term", t, "type", "Person")).toList());
    }

    @Test
    void anUnsavedStageIsEmpty() {
        Map<String, Object> out = service.stage("kb", OntologyStage.VOCABULARY);
        assertEquals("EMPTY", out.get("status"));
        assertEquals(0, out.get("version"));
    }

    @Test
    void savingStoresADraftAndBumpsTheVersionOnlyOnRealChanges() {
        service.save("kb", OntologyStage.SOURCE, source());
        Map<String, Object> again = service.save("kb", OntologyStage.SOURCE, source());
        assertEquals(1, again.get("version"));
        Map<String, Object> changed = service.save("kb", OntologyStage.SOURCE, Map.of("kind", "text", "text", "More."));
        assertEquals(2, changed.get("version"));
        assertEquals("DRAFT", changed.get("status"));
    }

    @Test
    void aStageCannotBeApprovedBeforeThePreviousOne() {
        service.save("kb", OntologyStage.VOCABULARY, vocab("Napoleon"));
        Map<String, Object> out = service.approve("kb", OntologyStage.VOCABULARY);
        assertEquals(false, out.get("approved"));
        assertTrue(out.get("errors").toString().contains("Approve the source stage first"));
    }

    @Test
    void invalidContentIsReportedAndKeepsTheStageUnapproved() {
        service.save("kb", OntologyStage.SOURCE, source());
        assertEquals(true, service.approve("kb", OntologyStage.SOURCE).get("approved"));
        service.save("kb", OntologyStage.VOCABULARY, Map.of("terms", List.of()));
        Map<String, Object> out = service.approve("kb", OntologyStage.VOCABULARY);
        assertEquals(false, out.get("approved"));
        assertEquals("DRAFT", service.stage("kb", OntologyStage.VOCABULARY).get("status"));
    }

    @Test
    void changingAnApprovedStageMarksLaterStagesStale() {
        service.save("kb", OntologyStage.SOURCE, source());
        service.approve("kb", OntologyStage.SOURCE);
        service.save("kb", OntologyStage.VOCABULARY, vocab("Napoleon"));
        assertEquals(true, service.approve("kb", OntologyStage.VOCABULARY).get("approved"));

        service.save("kb", OntologyStage.SOURCE, Map.of("kind", "text", "text", "Different text."));

        assertEquals("DRAFT", service.stage("kb", OntologyStage.SOURCE).get("status"));
        assertEquals("STALE", service.stage("kb", OntologyStage.VOCABULARY).get("status"));
        assertFalse(service.approvedPayloads("kb").containsKey(OntologyStage.VOCABULARY));
    }

    @Test
    void savingReportsWhatStillBlocksApproval() {
        Map<String, Object> out = service.save("kb", OntologyStage.SOURCE, Map.of());
        assertEquals(1, ((List<?>) out.get("errors")).size());
    }

    @Test
    void resetRemovesEveryStageOfTheLabelOnly() {
        service.save("kb", OntologyStage.SOURCE, source());
        service.save("other", OntologyStage.SOURCE, source());
        service.reset("kb");
        assertEquals("EMPTY", service.stage("kb", OntologyStage.SOURCE).get("status"));
        assertEquals("DRAFT", service.stage("other", OntologyStage.SOURCE).get("status"));
        assertEquals(Optional.empty(), rows.stream().filter(d -> d.getLabel().equals("kb")).findAny());
    }
}
