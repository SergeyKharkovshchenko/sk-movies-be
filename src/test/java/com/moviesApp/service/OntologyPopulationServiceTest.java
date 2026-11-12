package com.moviesApp.service;

import com.moviesApp.rag.EntityExtractorService.Triple;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyPopulationServiceTest {

    private static final Map<String, Object> VOCAB = Map.of("terms", List.of(
            Map.of("term", "Napoleon", "type", "Person", "aliases", List.of("Bonaparte")),
            Map.of("term", "Waterloo", "type", "Event", "aliases", List.of())));
    private static final Map<String, Object> ONTOLOGY = Map.of(
            "classes", List.of(Map.of("name", "Person"), Map.of("name", "Event")),
            "relations", List.of(Map.of("name", "FOUGHT_AT", "domain", "Person", "range", "Event")));

    @Test
    void anAliasInATripleIsStoredUnderThePreferredTerm() {
        OntologyPopulationService.Decision d = OntologyPopulationService.decide(
                new Triple("Bonaparte", "FOUGHT_AT", "Waterloo"), VOCAB, ONTOLOGY);
        assertTrue(d.accepted());
        assertEquals("Napoleon", d.subject());
    }

    @Test
    void aTripleOutsideTheOntologyIsNotAcceptedAndSaysWhy() {
        OntologyPopulationService.Decision unknownTerm = OntologyPopulationService.decide(
                new Triple("Blücher", "FOUGHT_AT", "Waterloo"), VOCAB, ONTOLOGY);
        assertFalse(unknownTerm.accepted());
        assertEquals("unknown term \"Blücher\"", unknownTerm.reason());

        OntologyPopulationService.Decision unknownRelation = OntologyPopulationService.decide(
                new Triple("Napoleon", "LOST", "Waterloo"), VOCAB, ONTOLOGY);
        assertEquals("relation LOST is not in the ontology", unknownRelation.reason());
    }

    @Test
    void thePromptListsEveryAllowedRelationWithItsClasses() {
        assertEquals(List.of("FOUGHT_AT (Person -> Event)"), OntologyPopulationService.allowedRelations(ONTOLOGY));
    }

    @Test
    void extractionSeesEveryKnownSpellingOnce() {
        assertEquals(List.of("Napoleon", "Bonaparte", "Waterloo"), OntologyPopulationService.surfaceForms(VOCAB));
    }
}
