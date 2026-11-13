package com.aicompany.backend.harness.library;

import com.aicompany.backend.harness.model.HarnessResource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PHASE 35 (ADR-041 §7): which skills and knowledge entries are pertinent to a task.
 * One deterministic rule, in one place, so the same task always gets the same choice
 * and a person can say why.
 *
 * <p>The words of the task (its title and description) are compared with the words of
 * each entry: a task word found in the key, the name or the tags scores 2, one found
 * only in the description scores 1. An entry is pertinent from {@link #THRESHOLD} up --
 * one word of the name, or two of the description. Words are lower case, without
 * accents, at least three letters, and not function words; two words match when they
 * are equal or, from five letters up, share all but their last two letters (and at
 * least five): {@code migrazione} and {@code migrazioni}, {@code migration} and
 * {@code migrations} match, {@code testo} and {@code test} do not.
 */
public final class Pertinence {

    /** From this score an entry is pertinent. */
    public static final int THRESHOLD = 2;

    private static final Set<String> FUNCTION_WORDS = Set.of(
            // Italian
            "che", "con", "per", "tra", "fra", "una", "uno", "del", "dei", "gli", "nel", "sul", "dal", "non", "piu",
            "della", "delle", "dello", "degli", "nella", "nelle", "nello", "negli", "sulla", "sulle", "sullo", "sugli",
            "alla", "alle", "allo", "agli", "dalla", "dalle", "dallo", "dagli", "come", "solo", "dopo", "prima", "poi",
            "anche", "tutto", "tutti", "tutta", "tutte", "ogni", "questo", "questa", "questi", "queste", "quello",
            "quella", "quelli", "quelle", "sono", "essere", "suoi", "sue", "suo", "sua", "loro", "cosa", "senza",
            "quando", "dove", "mentre", "oppure", "ancora", "gia", "molto", "poco",
            // English
            "the", "and", "for", "with", "from", "that", "this", "into", "your", "are", "was", "were", "its", "not",
            "but", "all", "any", "can", "you", "our", "has", "have", "will", "should", "must", "when", "then");

    private Pertinence() {
    }

    /** How pertinent an entry is to a task's text; 0 when nothing matches. */
    public static int score(String task, HarnessResource resource) {
        return score(words(task), resource);
    }

    /**
     * At most {@code max} of the candidates that are pertinent to the task, the most
     * pertinent first; equal scores keep the candidates' order.
     */
    public static List<HarnessResource> select(String task, List<HarnessResource> candidates, int max) {
        Set<String> taskWords = words(task);
        List<int[]> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            int score = score(taskWords, candidates.get(i));
            if (score >= THRESHOLD) {
                ranked.add(new int[] {i, score});
            }
        }
        ranked.sort(Comparator.comparingInt((int[] r) -> -r[1]).thenComparingInt(r -> r[0]));
        return ranked.stream().limit(Math.max(0, max)).map(r -> candidates.get(r[0])).toList();
    }

    private static int score(Set<String> taskWords, HarnessResource resource) {
        Set<String> strong = words(resource.getKey().replace('-', ' ') + " " + nullToEmpty(resource.getName()) + " "
                + String.join(" ", resource.getTags()));
        Set<String> weak = words(resource.getDescription());
        int score = 0;
        for (String word : taskWords) {
            if (strong.stream().anyMatch(w -> same(word, w))) {
                score += 2;
            } else if (weak.stream().anyMatch(w -> same(word, w))) {
                score += 1;
            }
        }
        return score;
    }

    /** The comparable words of a text, in order, each once. */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String word : plain.split("[^a-z0-9]+")) {
            if (word.length() >= 3 && !FUNCTION_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /** Equal, or -- from five letters up -- the same but for an ending of at most two letters. */
    static boolean same(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < 5 || b.length() < 5) {
            return false;
        }
        int common = 0;
        int limit = Math.min(a.length(), b.length());
        while (common < limit && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= Math.max(5, limit - 2);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
