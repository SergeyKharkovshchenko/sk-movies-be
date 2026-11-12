package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.rag.OpenAiService;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OntologyProposalServiceTest {

    private static final String TEXT =
            "Napoleon Bonaparte was the French Emperor. Napoleon fought at Waterloo. "
                    + "Wellington commanded the Allied Army. The Battle of Waterloo ended the Hundred Days.";

    private static Map<String, Object> entity(String name, String type) {
        return Map.of("name", name, "type", type);
    }

    private static Map<String, Object> hint(String from, String predicate, String to) {
        return Map.of("from", from, "predicate", predicate, "to", to, "evidence", "x");
    }

    private final OntologyDraftService drafts = mock(OntologyDraftService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final OpenAiService openAi = mock(OpenAiService.class);
    private final OntologyProposalService service =
            new OntologyProposalService(drafts, knowledge, openAi, new ObjectMapper());

    @Test
    void textVocabularyKeepsOnlyAliasesAndDefinitionsThatAreInTheSource() throws Exception {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("entities", List.of(entity("Napoleon", "Person"), entity("Waterloo", "Event")));
        graph.put("sections", List.of(Map.of("title", "Career", "text", TEXT)));
        graph.put("entityRelationships", List.of(hint("Napoleon", "FOUGHT_AT", "Waterloo")));
        when(knowledge.suggestGraph(TEXT)).thenReturn(graph);
        when(openAi.chatDesign(anyString(), anyString(), anyInt())).thenReturn("""
                {"terms": [
                  {"term": "Napoleon", "aliases": ["Napoleon Bonaparte", "Boney", "Waterloo"],
                   "definition": "Napoleon Bonaparte was the French Emperor."},
                  {"term": "Waterloo", "aliases": [],
                   "definition": "Waterloo is the greatest battle ever fought."}
                ]}""");

        Map<String, Object> out = invokeVocabulary();

        List<Map<String, Object>> terms = OntologyRules.listOf(out, "terms");
        Map<String, Object> napoleon = terms.get(0);
        // "Boney" is not in the text, "Waterloo" is another term's name
        assertEquals(List.of("Napoleon Bonaparte"), napoleon.get("aliases"));
        assertEquals("Napoleon Bonaparte was the French Emperor.", napoleon.get("definition"));
        // an invented definition is dropped, not kept
        assertEquals("", terms.get(1).get("definition"));
        assertEquals(1, OntologyRules.listOf(out, "sections").size());
        assertEquals(1, OntologyRules.listOf(out, "relationshipHints").size());
    }

    private Map<String, Object> invokeVocabulary() throws Exception {
        Map<OntologyStage, Map<String, Object>> approved = new EnumMap<>(OntologyStage.class);
        approved.put(OntologyStage.SOURCE, Map.of("kind", "text", "text", TEXT));
        when(drafts.approvedPayloads("kb")).thenReturn(approved);
        when(drafts.save(anyString(), any(OntologyStage.class), any())).thenAnswer(inv -> inv.getArgument(2));
        return service.propose("kb", OntologyStage.VOCABULARY);
    }

    @Test
    void aStageCannotBeProposedBeforeThePreviousOneIsApproved() {
        when(drafts.approvedPayloads("kb")).thenReturn(new EnumMap<>(OntologyStage.class));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.propose("kb", OntologyStage.VOCABULARY));
        assertTrue(e.getMessage().contains("source"));
        assertThrows(IllegalArgumentException.class, () -> service.propose("kb", OntologyStage.SOURCE));
    }

    @Test
    void ontologyIsDerivedFromTypedEvidenceBackedHintsOnly() {
        Map<String, Object> vocab = Map.of(
                "terms", List.of(
                        Map.of("term", "Napoleon", "type", "Person", "aliases", List.of("Bonaparte")),
                        Map.of("term", "Waterloo", "type", "Event", "aliases", List.of()),
                        Map.of("term", "Elba", "type", "Place", "aliases", List.of())),
                "relationshipHints", List.of(
                        hint("Bonaparte", "fought at", "Waterloo"),
                        hint("Napoleon", "EXILED_TO", "Elba"),
                        hint("Napoleon", "LOVED", "Josephine")));

        Map<String, Object> ontology = OntologyProposalService.deriveOntology(vocab);

        List<String> relations = OntologyRules.listOf(ontology, "relations").stream()
                .map(r -> r.get("name") + ":" + r.get("domain") + "->" + r.get("range")).toList();
        assertEquals(List.of("FOUGHT_AT:Person->Event", "EXILED_TO:Person->Place"), relations);
        assertEquals(3, OntologyRules.listOf(ontology, "classes").size());
        // what it derives must itself be approvable
        assertEquals(List.of(), OntologyRules.validateOntology(ontology, vocab));
    }

    @Test
    void csvTaxonomyAndOntologyFollowTheUsersEditsToTheVocabulary() {
        Map<String, Object> vocab = Map.of("terms", List.of(
                Map.of("term", "Tiramisu", "type", "Dish", "aliases", List.of()),
                Map.of("term", "dairy", "type", "Category", "aliases", List.of())));
        Map<String, Object> taxonomy = Map.of("pairs", List.of(
                Map.of("parent", "dairy", "child", "mascarpone"),
                Map.of("parent", "dairy", "child", "Tiramisu")));
        assertEquals(1, OntologyRules.listOf(OntologyProposalService.restrictTaxonomy(taxonomy, vocab), "pairs").size());

        Map<String, Object> ontology = Map.of(
                "classes", List.of(Map.of("name", "Dish"), Map.of("name", "Ingredient")),
                "relations", List.of(
                        Map.of("name", "INCLUDES_INGREDIENT", "domain", "Dish", "range", "Ingredient"),
                        Map.of("name", "OF_CUISINE", "domain", "Dish", "range", "Dish")));
        Map<String, Object> restricted = OntologyProposalService.restrictOntology(ontology, vocab);
        List<String> classes = OntologyRules.listOf(restricted, "classes").stream().map(c -> (String) c.get("name")).toList();
        // Ingredient is gone (no term uses it), Category appeared (a term uses it)
        assertTrue(classes.containsAll(List.of("Dish", "Category")));
        assertFalse(classes.contains("Ingredient"));
        assertEquals(List.of("OF_CUISINE"),
                OntologyRules.listOf(restricted, "relations").stream().map(r -> (String) r.get("name")).toList());
    }
}
