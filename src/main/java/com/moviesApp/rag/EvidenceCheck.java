package com.moviesApp.rag;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Closed-world guard for LLM-extracted relationships: a relationship is kept only when the LLM's
 * quoted evidence sentence really occurs in the source text and names both of its entities.
 * The prompts already forbid outside knowledge, but a model that "knows" two entities are
 * connected in the real world (e.g. an actor and a film the text never links) still emits the
 * pair -- this makes the rule hold mechanically instead of by instruction alone.
 */
public final class EvidenceCheck {

    // A sentence boundary inside the quote ("... Inception. Tom Hardy ...") -- one sentence only,
    // so a quote can't stitch together two neighbouring sentences that each name one entity.
    private static final Pattern INNER_SENTENCE_BREAK = Pattern.compile("[.!?]\\s+\\p{Lu}");
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(the|a|an)\\s+");

    private EvidenceCheck() {}

    /** Returns null when the evidence supports the relationship, otherwise the reason it doesn't. */
    public static String reject(String sourceText, String evidence, String subject, String object) {
        if (evidence == null || evidence.isBlank()) return "no evidence sentence";
        String ev = normalize(evidence);
        if (INNER_SENTENCE_BREAK.matcher(evidence.strip().replaceAll("\\s+", " ")).find()) {
            return "evidence spans more than one sentence";
        }
        if (!normalize(sourceText).contains(stripEndPunctuation(ev))) return "evidence not found in the text";
        if (!mentions(ev, subject)) return "evidence doesn't mention '" + subject + "'";
        if (!mentions(ev, object)) return "evidence doesn't mention '" + object + "'";
        return null;
    }

    /** True when the entity name occurs in the source text (closed world for entities, too). */
    public static boolean occursIn(String sourceText, String name) {
        return mentions(normalize(sourceText), name);
    }

    // Full name (optionally without a leading article), or -- for multi-word names -- its last
    // word when that's distinctive enough ("Nolan directed ..." for "Christopher Nolan").
    private static boolean mentions(String normalizedText, String name) {
        if (name == null || name.isBlank()) return false;
        String n = normalize(name);
        if (containsPhrase(normalizedText, n)) return true;
        String noArticle = LEADING_ARTICLE.matcher(n).replaceFirst("");
        if (!noArticle.equals(n) && containsPhrase(normalizedText, noArticle)) return true;
        String[] words = noArticle.split(" ");
        if (words.length >= 2) {
            String last = words[words.length - 1];
            return last.length() >= 4 && containsPhrase(normalizedText, last);
        }
        return false;
    }

    private static boolean containsPhrase(String text, String phrase) {
        if (phrase.isEmpty()) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static String stripEndPunctuation(String s) {
        return s.replaceAll("[\\s.!?;,:]+$", "");
    }

    /** Lower-case, unify quotes/dashes, strip accents, collapse whitespace (source text has hard line breaks). */
    static String normalize(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[‘’‛′`]", "'").replaceAll("[“”„″]", "\"")
                .replaceAll("[‐-―]", "-");
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
