package com.moviesApp.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyRulesTest {

    private static Map<String, Object> term(String term, String type, String... aliases) {
        return Map.of("term", term, "type", type, "aliases", List.of(aliases));
    }

    private static final Map<String, Object> VOCAB = Map.of("terms", List.of(
            term("Napoleon", "Person", "Bonaparte", "Napoleon Bonaparte"),
            term("Wellington", "Person"),
            term("Waterloo", "Battle"),
            term("French Army", "Army")));

    private static final Map<String, Object> ONTOLOGY = Map.of(
            "classes", List.of(
                    Map.of("name", "Agent"),
                    Map.of("name", "Person", "parent", "Agent"),
                    Map.of("name", "Battle"),
                    Map.of("name", "Army", "parent", "Agent")),
            "relations", List.of(
                    Map.of("name", "FOUGHT_AT", "domain", "Agent", "range", "Battle"),
                    Map.of("name", "COMMANDED", "domain", "Person", "range", "Army")));

    @Test
    void anAliasResolvesToItsPreferredTerm() {
        assertEquals("Napoleon", OntologyRules.resolve(VOCAB, "  bonaparte "));
        assertEquals("Napoleon", OntologyRules.resolve(VOCAB, "NAPOLEON"));
        assertNull(OntologyRules.resolve(VOCAB, "Blücher"));
    }

    @Test
    void vocabularyRejectsDuplicatesAcrossTermsAndAliases() {
        Map<String, Object> bad = Map.of("terms", List.of(
                term("Napoleon", "Person", "Boney"),
                term("Bonaparte Jr", "Person", "boney")));
        List<String> errors = OntologyRules.validateVocabulary(bad);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("boney"));
    }

    @Test
    void vocabularyNeedsTermsAndTypes() {
        assertEquals(1, OntologyRules.validateVocabulary(Map.of()).size());
        List<String> errors = OntologyRules.validateVocabulary(Map.of("terms", List.of(Map.of("term", "X", "type", ""))));
        assertTrue(errors.get(0).contains("no type"));
    }

    @Test
    void taxonomyChecksTermsSingleParentAndCycles() {
        Map<String, Object> pairs = Map.of("pairs", List.of(
                Map.of("parent", "Napoleon", "child", "Wellington"),
                Map.of("parent", "Waterloo", "child", "Wellington"),
                Map.of("parent", "Ghost", "child", "Waterloo")));
        List<String> errors = OntologyRules.validateTaxonomy(pairs, VOCAB);
        assertTrue(errors.stream().anyMatch(e -> e.contains("two parents")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"Ghost\" is not in the vocabulary")));

        Map<String, Object> cycle = Map.of("pairs", List.of(
                Map.of("parent", "Napoleon", "child", "Wellington"),
                Map.of("parent", "Wellington", "child", "Napoleon")));
        assertTrue(OntologyRules.validateTaxonomy(cycle, VOCAB).stream().anyMatch(e -> e.contains("cycle")));
    }

    @Test
    void ontologyMustDeclareEveryTermsClassAndKnownDomainsAndRanges() {
        assertEquals(List.of(), OntologyRules.validateOntology(ONTOLOGY, VOCAB));

        Map<String, Object> bad = Map.of(
                "classes", List.of(Map.of("name", "Person")),
                "relations", List.of(
                        Map.of("name", "fought_at", "domain", "Person", "range", "Person"),
                        Map.of("name", "LED", "domain", "Person", "range", "Nowhere")));
        List<String> errors = OntologyRules.validateOntology(bad, VOCAB);
        assertTrue(errors.stream().anyMatch(e -> e.contains("UPPER_SNAKE_CASE")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown range \"Nowhere\"")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"Waterloo\" has type \"Battle\"")));
    }

    @Test
    void metadataAttributesNeedUniqueNamesAndKnownDatatypes() {
        Map<String, Object> p = Map.of("entityAttributes", List.of(
                Map.of("name", "born", "datatype", "date"),
                Map.of("name", "Born", "datatype", "date"),
                Map.of("name", "age", "datatype", "integer")));
        List<String> errors = OntologyRules.validateMetadata(p);
        assertEquals(2, errors.size());
    }

    @Test
    void strictTripleCheckAcceptsDeclaredRelationsAndSubclasses() {
        // Napoleon is a Person, a subclass of Agent, so an Agent-domain relation allows him
        assertNull(OntologyRules.checkTriple("Bonaparte", "FOUGHT_AT", "Waterloo", VOCAB, ONTOLOGY));
        assertNull(OntologyRules.checkTriple("Wellington", "COMMANDED", "French Army", VOCAB, ONTOLOGY));
    }

    @Test
    void strictTripleCheckExplainsEveryRejection() {
        assertEquals("unknown term \"Blücher\"",
                OntologyRules.checkTriple("Blücher", "FOUGHT_AT", "Waterloo", VOCAB, ONTOLOGY));
        assertEquals("relation DEFEATED is not in the ontology",
                OntologyRules.checkTriple("Napoleon", "DEFEATED", "Wellington", VOCAB, ONTOLOGY));
        assertEquals("COMMANDED does not allow Person -> Battle",
                OntologyRules.checkTriple("Napoleon", "COMMANDED", "Waterloo", VOCAB, ONTOLOGY));
    }
}
