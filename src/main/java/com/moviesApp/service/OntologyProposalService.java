package com.moviesApp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviesApp.rag.EvidenceCheck;
import com.moviesApp.rag.OpenAiService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.moviesApp.service.OntologyRules.listOf;
import static com.moviesApp.service.OntologyRules.str;
import static com.moviesApp.service.OntologyRules.strings;

/**
 * Proposes each stage of the pipeline from the previous approved ones, for the user to edit. CSV
 * sources are derived deterministically ({@link CsvOntologyProposer}); text sources use the LLM,
 * always held to the closed world: only what the text itself says survives.
 */
@Service
public class OntologyProposalService {

    static final List<String> PROVENANCE = List.of("source_section", "chunk", "evidence", "origin");

    private static final String VOCAB_PROMPT = """
            You get the SOURCE TEXT of a knowledge base and a list of entity names taken from it.
            For each entity return:
            - "aliases": other names, spellings or abbreviations by which THE TEXT ITSELF refers to the
              same entity, each exactly as written in the text. [] when the text uses only one name.
            - "definition": ONE sentence copied verbatim from the text that names the entity and says
              the most about what it is. "" when no sentence does. Never write a definition yourself.

            CLOSED WORLD: use only the text. Never add an alias or a fact from your own knowledge.
            Return ONLY a JSON object, no markdown:
            {"terms": [{"term": "Entity Name", "aliases": ["..."], "definition": "..."}]}
            """;

    private final OntologyDraftService drafts;
    private final KnowledgeService knowledge;
    private final OpenAiService openAi;
    private final ObjectMapper mapper;

    public OntologyProposalService(OntologyDraftService drafts, KnowledgeService knowledge,
                                   OpenAiService openAi, ObjectMapper mapper) {
        this.drafts = drafts;
        this.knowledge = knowledge;
        this.openAi = openAi;
        this.mapper = mapper;
    }

    /** Builds a proposal for the stage and saves it as that stage's draft. */
    public Map<String, Object> propose(String label, OntologyStage stage) throws Exception {
        if (stage == OntologyStage.SOURCE || stage == OntologyStage.POPULATION) {
            throw new IllegalArgumentException("The " + stage.key() + " stage is not proposed, it is provided.");
        }
        Map<OntologyStage, Map<String, Object>> approved = drafts.approvedPayloads(label);
        OntologyStage prev = stage.previous().orElseThrow();
        if (!approved.containsKey(prev)) {
            throw new IllegalStateException("Approve the " + prev.key() + " stage first.");
        }
        Map<String, Object> source = approved.get(OntologyStage.SOURCE);
        boolean csv = isCsv(source);
        Map<String, Object> vocabulary = approved.get(OntologyStage.VOCABULARY);

        Map<String, Object> payload = switch (stage) {
            case VOCABULARY -> csv ? csvStage(source, "vocabulary") : textVocabulary(str(source, "text"));
            case METADATA -> csv ? csvStage(source, "metadata") : textMetadata(str(source, "text"), vocabulary);
            case TAXONOMY -> csv
                    ? restrictTaxonomy(csvStage(source, "taxonomy"), vocabulary)
                    : textTaxonomy(str(source, "text"), vocabulary);
            case ONTOLOGY -> csv
                    ? restrictOntology(csvStage(source, "ontology"), vocabulary)
                    : deriveOntology(vocabulary);
            default -> throw new IllegalArgumentException("unreachable");
        };
        return drafts.save(label, stage, payload);
    }

    static boolean isCsv(Map<String, Object> source) {
        return str(source, "text").isEmpty() && !listOf(source, "files").isEmpty();
    }

    private static Map<String, Object> csvStage(Map<String, Object> source, String key) {
        List<Map<String, String>> files = new ArrayList<>();
        for (Map<String, Object> f : listOf(source, "files")) {
            files.add(Map.of("name", str(f, "name"), "content", f.get("content") == null ? "" : String.valueOf(f.get("content"))));
        }
        return CsvOntologyProposer.propose(files).get(key);
    }

    // ── text: vocabulary ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> textVocabulary(String text) throws Exception {
        Map<String, Object> graph = knowledge.suggestGraph(text);
        List<Map<String, Object>> entities = listOf(graph, "entities");
        List<String> names = entities.stream().map(e -> str(e, "name")).filter(n -> !n.isEmpty()).toList();

        Map<String, Map<String, Object>> enrichment = names.isEmpty() ? Map.of() : enrich(text, names);
        Set<String> taken = new LinkedHashSet<>();
        names.forEach(n -> taken.add(n.toLowerCase(Locale.ROOT)));

        List<Map<String, Object>> terms = new ArrayList<>();
        for (Map<String, Object> e : entities) {
            String name = str(e, "name");
            if (name.isEmpty()) continue;
            Map<String, Object> extra = enrichment.getOrDefault(name, Map.of());
            List<String> aliases = new ArrayList<>();
            for (String alias : strings(extra.get("aliases"))) {
                String key = alias.toLowerCase(Locale.ROOT);
                if (taken.contains(key) || !EvidenceCheck.occursIn(text, alias)) continue;
                taken.add(key);
                aliases.add(alias);
            }
            String definition = str(extra, "definition");
            if (!definition.isEmpty() && EvidenceCheck.reject(text, definition, name, name) != null) definition = "";

            Map<String, Object> term = new LinkedHashMap<>();
            term.put("term", name);
            term.put("type", str(e, "type").isEmpty() ? "Entity" : str(e, "type"));
            term.put("aliases", aliases);
            term.put("definition", definition);
            term.put("origin", "text");
            terms.add(term);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terms", terms);
        // By-products of the analysis that later stages use: the source split into sections (what
        // population extracts from) and the evidence-backed relationships (what the ontology is derived from).
        out.put("sections", listOf(graph, "sections"));
        out.put("relationshipHints", listOf(graph, "entityRelationships"));
        out.put("dropped", Map.of(
                "entities", graph.getOrDefault("droppedEntities", List.of()),
                "relationships", graph.getOrDefault("droppedRelationships", List.of())));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> enrich(String text, List<String> names) throws Exception {
        String input = "SOURCE TEXT:\n" + text + "\n\nENTITY NAMES:\n" + String.join("\n", names);
        String response = openAi.chatDesign(VOCAB_PROMPT, input, 16000).strip();
        if (response.startsWith("```")) {
            response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        Map<String, Object> parsed = mapper.readValue(response, Map.class);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> t : listOf(parsed, "terms")) out.put(str(t, "term"), t);
        return out;
    }

    // ── text: metadata, taxonomy, ontology ──────────────────────────────────────

    private static final String METADATA_PROMPT = """
            You get the SOURCE TEXT of a knowledge base and the entity TYPES in it, each with a few
            example entity names. Propose the extra attributes (properties) worth keeping on entities of
            these types: facts that the text states as a value, such as a price, time, date, duration,
            count, amount or status.
            For each attribute return:
            - "name": snake_case, e.g. "walking_minutes"
            - "datatype": one of string, number, date, boolean
            - "appliesTo": the types (from the list) whose entities carry it
            - "description": a few words on what it means
            - "example": ONE sentence copied verbatim from the text that states a value of this attribute
              for an entity. Never write it yourself.

            CLOSED WORLD: only attributes whose value the text actually states. Skip an attribute when
            you cannot quote such a sentence. Do not propose "name", "description" or provenance fields.
            Return ONLY a JSON object, no markdown:
            {"attributes": [{"name": "...", "datatype": "...", "appliesTo": ["..."], "description": "...", "example": "..."}]}
            """;

    private Map<String, Object> textMetadata(String text, Map<String, Object> vocabulary) throws Exception {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Map<String, Object> t : listOf(vocabulary, "terms")) {
            String type = str(t, "type");
            if (type.isEmpty() || str(t, "term").isEmpty()) continue;
            List<String> names = byType.computeIfAbsent(type, k -> new ArrayList<>());
            if (names.size() < 4) names.add(str(t, "term"));
        }
        List<Map<String, Object>> attributes = List.of();
        if (!byType.isEmpty() && !text.isBlank()) {
            StringBuilder types = new StringBuilder();
            byType.forEach((type, names) ->
                    types.append("- ").append(type).append(": ").append(String.join(", ", names)).append('\n'));
            String response = openAi.chatDesign(METADATA_PROMPT,
                    "SOURCE TEXT:\n" + text + "\n\nTYPES:\n" + types, 4000).strip();
            if (response.startsWith("```")) {
                response = response.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(response, Map.class);
            attributes = checkedAttributes(parsed, text, byType.keySet());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provenance", PROVENANCE);
        out.put("entityAttributes", attributes);
        out.put("relationAttributes", List.of());
        return out;
    }

    /**
     * Keeps a suggested attribute only if it is well formed and its example sentence really occurs in
     * the text (closed world: no attribute from the model's own knowledge). Types it names that the
     * vocabulary does not have are dropped; an empty appliesTo means every type.
     */
    static List<Map<String, Object>> checkedAttributes(Map<String, Object> parsed, String text, Set<String> types) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(PROVENANCE);
        seen.add("name");
        seen.add("description");
        for (Map<String, Object> a : listOf(parsed, "attributes")) {
            String name = str(a, "name").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            String datatype = str(a, "datatype").toLowerCase(Locale.ROOT);
            String example = str(a, "example");
            if (name.isEmpty() || !OntologyRules.DATATYPES.contains(datatype) || !seen.add(name)) continue;
            EvidenceCheck.Verbatim v = EvidenceCheck.keepVerbatim(text, example);
            if (v.kept().isBlank() || !v.dropped().isEmpty()) continue;
            List<String> applies = new ArrayList<>();
            for (String t : strings(a.get("appliesTo"))) {
                types.stream().filter(x -> x.equalsIgnoreCase(t.strip())).findFirst()
                        .filter(x -> !applies.contains(x)).ifPresent(applies::add);
            }
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("name", name);
            attribute.put("datatype", datatype);
            attribute.put("required", false);
            attribute.put("appliesTo", applies);
            attribute.put("description", str(a, "description"));
            attribute.put("example", example);
            out.add(attribute);
        }
        return out;
    }

    private Map<String, Object> textTaxonomy(String text, Map<String, Object> vocabulary) throws Exception {
        List<String> names = listOf(vocabulary, "terms").stream().map(t -> str(t, "term")).filter(n -> !n.isEmpty()).toList();
        Map<String, Object> proposal = knowledge.proposeTaxonomy(names, text);
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Map<String, Object> p : listOf(proposal, "pairs")) {
            pairs.add(Map.of("parent", str(p, "parent"), "child", str(p, "child"), "evidence", str(p, "evidence")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pairs", pairs);
        out.put("dropped", proposal.getOrDefault("dropped", List.of()));
        return out;
    }

    /** Classes are the vocabulary's types; relations are the evidence-backed hints typed by their ends. */
    static Map<String, Object> deriveOntology(Map<String, Object> vocabulary) {
        Map<String, String> types = OntologyRules.termTypes(vocabulary);
        Set<String> classes = new LinkedHashSet<>(types.values());
        classes.remove("");
        Map<String, Map<String, Object>> relations = new LinkedHashMap<>();
        for (Map<String, Object> hint : listOf(vocabulary, "relationshipHints")) {
            String from = OntologyRules.resolve(vocabulary, str(hint, "from"));
            String to = OntologyRules.resolve(vocabulary, str(hint, "to"));
            String predicate = str(hint, "predicate").toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9]", "_").replaceAll("_{2,}", "_").replaceAll("^_|_$", "");
            if (from == null || to == null || predicate.isEmpty()) continue;
            String domain = types.get(from), range = types.get(to);
            if (domain == null || domain.isEmpty() || range == null || range.isEmpty()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", predicate);
            r.put("domain", domain);
            r.put("range", range);
            r.put("description", "");
            relations.putIfAbsent(predicate + "|" + domain + "|" + range, r);
        }
        List<Map<String, Object>> classList = new ArrayList<>();
        for (String c : classes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c);
            m.put("parent", "");
            m.put("description", "");
            classList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classes", classList);
        out.put("relations", List.copyOf(relations.values()));
        return out;
    }

    // ── csv: keep the proposals consistent with the user's approved vocabulary ──

    /** Taxonomy pairs whose both ends are still vocabulary terms (the user may have removed some). */
    static Map<String, Object> restrictTaxonomy(Map<String, Object> proposal, Map<String, Object> vocabulary) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> pair : listOf(proposal, "pairs")) {
            String parent = OntologyRules.resolve(vocabulary, str(pair, "parent"));
            String child = OntologyRules.resolve(vocabulary, str(pair, "child"));
            if (parent != null && child != null) {
                kept.add(Map.of("parent", parent, "child", child, "evidence", str(pair, "evidence")));
            }
        }
        return Map.of("pairs", kept);
    }

    /** The proposed ontology plus any class the approved vocabulary uses, minus relations left dangling. */
    static Map<String, Object> restrictOntology(Map<String, Object> proposal, Map<String, Object> vocabulary) {
        Set<String> used = new LinkedHashSet<>(OntologyRules.termTypes(vocabulary).values());
        used.remove("");
        Map<String, Map<String, Object>> classes = new LinkedHashMap<>();
        for (Map<String, Object> c : listOf(proposal, "classes")) classes.put(str(c, "name"), c);
        for (String type : used) {
            classes.computeIfAbsent(type, t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t);
                m.put("parent", "");
                m.put("description", "");
                return m;
            });
        }
        // Classes no term uses any more (e.g. the user removed every term of a type) are dropped,
        // unless another class lists them as its parent.
        Set<String> parents = new LinkedHashSet<>();
        classes.values().forEach(c -> parents.add(str(c, "parent")));
        classes.keySet().removeIf(name -> !used.contains(name) && !parents.contains(name));
        List<Map<String, Object>> relations = listOf(proposal, "relations").stream()
                .filter(r -> classes.containsKey(str(r, "domain")) && classes.containsKey(str(r, "range")))
                .toList();
        return Map.of("classes", List.copyOf(classes.values()), "relations", relations);
    }
}
