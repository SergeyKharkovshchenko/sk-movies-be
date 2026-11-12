package com.moviesApp.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The stages of the ontology pipeline, in the order they must be approved. */
public enum OntologyStage {
    SOURCE, VOCABULARY, METADATA, TAXONOMY, ONTOLOGY, POPULATION;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<OntologyStage> parse(String key) {
        if (key == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.key().equals(key.toLowerCase(Locale.ROOT))).findFirst();
    }

    /** Stages that depend on this one (everything after it). */
    public List<OntologyStage> laterStages() {
        return Arrays.stream(values()).filter(s -> s.ordinal() > ordinal()).toList();
    }

    public Optional<OntologyStage> previous() {
        return ordinal() == 0 ? Optional.empty() : Optional.of(values()[ordinal() - 1]);
    }
}
