package com.moviesApp.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of the ontology pipeline as pure functions over each stage's JSON payload: what an
 * approvable stage must satisfy, how a mention resolves to a vocabulary term, and whether a triple
 * is allowed by the (strict) ontology.
 *
 * Payload shapes:
 * <pre>
 * vocabulary: {terms: [{term, type, aliases: [..], definition}]}
 * metadata:   {entityAttributes: [{name, datatype, required}], relationAttributes: [..]}
 * taxonomy:   {pairs: [{parent, child, evidence}]}
 * ontology:   {classes: [{name, parent, description}], relations: [{name, domain, range, description}]}
 * </pre>
 */
public final class OntologyRules {

    static final Set<String> DATATYPES = Set.of("string", "number", "date", "boolean");
    private static final Pattern RELATION_NAME = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    private OntologyRules() {}

    // ── payload accessors ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Map<String, Object> payload, String key) {
        if (payload == null || !(payload.get(key) instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).strip());
        }
        return out;
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ── vocabulary ──────────────────────────────────────────────────────────────

    /** lower-cased term or alias -> its preferred term. */
    static Map<String, String> termIndex(Map<String, Object> vocabulary) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Map<String, Object> t : listOf(vocabulary, "terms")) {
            String term = str(t, "term");
            if (term.isEmpty()) continue;
            index.putIfAbsent(norm(term), term);
            for (String alias : strings(t.get("aliases"))) index.putIfAbsent(norm(alias), term);
        }
        return index;
    }

    /** preferred term -> its class. */
    static Map<String, String> termTypes(Map<String, Object> vocabulary) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> t : listOf(vocabulary, "terms")) {
            if (!str(t, "term").isEmpty()) types.put(str(t, "term"), str(t, "type"));
        }
        return types;
    }

    /** The preferred term a mention stands for, or null when it is not in the vocabulary. */
    public static String resolve(Map<String, Object> vocabulary, String mention) {
        return mention == null ? null : termIndex(vocabulary).get(norm(mention));
    }

    // ── validation ──────────────────────────────────────────────────────────────

    /** Why the stage cannot be approved yet; empty when it can. */
    public static List<String> validate(OntologyStage stage, Map<String, Object> payload,
                                        Map<OntologyStage, Map<String, Object>> approved) {
        return switch (stage) {
            case SOURCE -> validateSource(payload);
            case VOCABULARY -> validateVocabulary(payload);
            case METADATA -> validateMetadata(payload);
            case TAXONOMY -> validateTaxonomy(payload, approved.get(OntologyStage.VOCABULARY));
            case ONTOLOGY -> validateOntology(payload, approved.get(OntologyStage.VOCABULARY));
            case POPULATION -> List.of();
        };
    }

    static List<String> validateSource(Map<String, Object> p) {
        boolean hasText = !str(p, "text").isEmpty();
        boolean hasFiles = !listOf(p, "files").isEmpty();
        return hasText || hasFiles ? List.of() : List.of("Add source text or at least one CSV file.");
    }

    static List<String> validateVocabulary(Map<String, Object> p) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> terms = listOf(p, "terms");
        if (terms.isEmpty()) errors.add("The vocabulary has no terms.");
        Map<String, String> seen = new HashMap<>();
        for (Map<String, Object> t : terms) {
            String term = str(t, "term");
            if (term.isEmpty()) {
                errors.add("A term has no name.");
                continue;
            }
            if (str(t, "type").isEmpty()) errors.add("\"" + term + "\" has no type (class).");
            List<String> names = new ArrayList<>(List.of(term));
            names.addAll(strings(t.get("aliases")));
            for (String n : names) {
                String owner = seen.putIfAbsent(norm(n), term);
                if (owner != null && !owner.equals(term)) {
                    errors.add("\"" + n + "\" is used by both \"" + owner + "\" and \"" + term + "\".");
                } else if (owner != null && n != term && norm(n).equals(norm(term))) {
                    errors.add("\"" + term + "\" lists itself as an alias.");
                }
            }
        }
        return errors;
    }

    static List<String> validateMetadata(Map<String, Object> p) {
        List<String> errors = new ArrayList<>();
        for (String key : List.of("entityAttributes", "relationAttributes")) {
            Set<String> names = new HashSet<>();
            for (Map<String, Object> a : listOf(p, key)) {
                String name = str(a, "name");
                if (name.isEmpty()) {
                    errors.add("An attribute has no name.");
                    continue;
                }
                if (!names.add(norm(name))) errors.add("Attribute \"" + name + "\" is listed twice.");
                if (!DATATYPES.contains(str(a, "datatype"))) {
                    errors.add("Attribute \"" + name + "\" needs a datatype: " + String.join(", ", DATATYPES) + ".");
                }
            }
        }
        return errors;
    }

    static List<String> validateTaxonomy(Map<String, Object> p, Map<String, Object> vocabulary) {
        List<String> errors = new ArrayList<>();
        Map<String, String> index = termIndex(vocabulary);
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Map<String, Object> pair : listOf(p, "pairs")) {
            String parent = str(pair, "parent"), child = str(pair, "child");
            if (parent.isEmpty() || child.isEmpty()) {
                errors.add("A taxonomy pair is missing its parent or child.");
                continue;
            }
            if (!index.containsKey(norm(parent))) errors.add("\"" + parent + "\" is not in the vocabulary.");
            if (!index.containsKey(norm(child))) errors.add("\"" + child + "\" is not in the vocabulary.");
            if (norm(parent).equals(norm(child))) {
                errors.add("\"" + child + "\" cannot be its own parent.");
                continue;
            }
            String existing = parentOf.putIfAbsent(child, parent);
            if (existing != null && !existing.equals(parent)) {
                errors.add("\"" + child + "\" has two parents: \"" + existing + "\" and \"" + parent + "\".");
            }
        }
        for (String node : parentOf.keySet()) {
            Set<String> seen = new HashSet<>();
            for (String cur = node; cur != null; cur = parentOf.get(cur)) {
                if (!seen.add(cur)) {
                    errors.add("The taxonomy contains a cycle through \"" + node + "\".");
                    break;
                }
            }
        }
        return errors.stream().distinct().toList();
    }

    static List<String> validateOntology(Map<String, Object> p, Map<String, Object> vocabulary) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> classes = listOf(p, "classes");
        if (classes.isEmpty()) errors.add("The ontology has no classes.");
        Set<String> classNames = new LinkedHashSet<>();
        Map<String, String> classParent = new LinkedHashMap<>();
        for (Map<String, Object> c : classes) {
            String name = str(c, "name");
            if (name.isEmpty()) {
                errors.add("A class has no name.");
                continue;
            }
            if (!classNames.add(name)) errors.add("Class \"" + name + "\" is listed twice.");
            if (!str(c, "parent").isEmpty()) classParent.put(name, str(c, "parent"));
        }
        classParent.forEach((child, parent) -> {
            if (!classNames.contains(parent)) errors.add("Class \"" + child + "\" has an unknown parent \"" + parent + "\".");
        });
        for (String node : classParent.keySet()) {
            Set<String> seen = new HashSet<>();
            for (String cur = node; cur != null; cur = classParent.get(cur)) {
                if (!seen.add(cur)) {
                    errors.add("The class hierarchy contains a cycle through \"" + node + "\".");
                    break;
                }
            }
        }

        Set<String> relationKeys = new HashSet<>();
        for (Map<String, Object> r : listOf(p, "relations")) {
            String name = str(r, "name"), domain = str(r, "domain"), range = str(r, "range");
            if (!RELATION_NAME.matcher(name).matches()) {
                errors.add("Relation \"" + name + "\" must be UPPER_SNAKE_CASE.");
                continue;
            }
            if (!classNames.contains(domain)) errors.add("Relation " + name + ": unknown domain \"" + domain + "\".");
            if (!classNames.contains(range)) errors.add("Relation " + name + ": unknown range \"" + range + "\".");
            if (!relationKeys.add(name + "|" + domain + "|" + range)) {
                errors.add("Relation " + name + " (" + domain + " -> " + range + ") is listed twice.");
            }
        }

        // Strict ontology: every vocabulary term must belong to a class the ontology declares.
        for (Map.Entry<String, String> e : termTypes(vocabulary).entrySet()) {
            if (!e.getValue().isEmpty() && !classNames.contains(e.getValue())) {
                errors.add("Term \"" + e.getKey() + "\" has type \"" + e.getValue() + "\", which is not an ontology class.");
            }
        }
        return errors.stream().distinct().toList();
    }

    // ── strict triple check ─────────────────────────────────────────────────────

    /** The class and all its ancestors (a Person-domain relation also allows an Actor subject). */
    static Set<String> withAncestors(String cls, Map<String, Object> ontology) {
        Map<String, String> parent = new HashMap<>();
        for (Map<String, Object> c : listOf(ontology, "classes")) {
            if (!str(c, "parent").isEmpty()) parent.put(str(c, "name"), str(c, "parent"));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String cur = cls; cur != null && out.add(cur); cur = parent.get(cur)) { /* walk up */ }
        return out;
    }

    /**
     * Null when the triple is allowed, otherwise why it is not. Strict: subject and object must be
     * vocabulary terms (or aliases), the predicate must be a declared relation, and the classes of
     * subject and object must fit that relation's domain and range (subclasses count).
     */
    public static String checkTriple(String subject, String predicate, String object,
                                     Map<String, Object> vocabulary, Map<String, Object> ontology) {
        String s = resolve(vocabulary, subject), o = resolve(vocabulary, object);
        if (s == null) return "unknown term \"" + subject + "\"";
        if (o == null) return "unknown term \"" + object + "\"";
        Map<String, String> types = termTypes(vocabulary);
        Set<String> subjectClasses = withAncestors(types.get(s), ontology);
        Set<String> objectClasses = withAncestors(types.get(o), ontology);
        boolean declared = false;
        for (Map<String, Object> r : listOf(ontology, "relations")) {
            if (!str(r, "name").equals(predicate)) continue;
            declared = true;
            if (subjectClasses.contains(str(r, "domain")) && objectClasses.contains(str(r, "range"))) return null;
        }
        if (!declared) return "relation " + predicate + " is not in the ontology";
        return predicate + " does not allow " + types.get(s) + " -> " + types.get(o);
    }
}
