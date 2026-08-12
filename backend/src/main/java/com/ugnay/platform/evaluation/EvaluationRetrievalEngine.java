package com.ugnay.platform.evaluation;

import com.ugnay.platform.discovery.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.ugnay.platform.evaluation.EvaluationModels.Algorithm;
import static com.ugnay.platform.evaluation.EvaluationModels.RunStatus;

/**
 * Four deterministic retrieval arms for frozen experiments. This engine has no
 * authority to update proposal routes, decisions, or plagiarism findings.
 */
@Component
public final class EvaluationRetrievalEngine {
    private static final int RESULT_LIMIT = 10;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "to", "with",
            "ang", "ay", "ito", "iyon", "mga", "ng", "para", "sa", "si", "yung", "isang", "maging", "gamit", "mula");
    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("baha", "flood"), Map.entry("pagbaha", "flood"), Map.entry("flooding", "flood"),
            Map.entry("sakuna", "disaster"), Map.entry("kalamidad", "disaster"),
            Map.entry("magsasaka", "farmer"), Map.entry("farmers", "farmer"),
            Map.entry("presyo", "price"), Map.entry("halaga", "price"),
            Map.entry("ospital", "hospital"), Map.entry("pasyente", "patient"),
            Map.entry("basura", "waste"), Map.entry("recycling", "waste"),
            Map.entry("estudyante", "student"), Map.entry("students", "student"),
            Map.entry("paaralan", "school"), Map.entry("campus", "school"));

    private final EmbeddingProvider embeddingProvider;
    private final String configuredModelSha256;
    private final String configuredTokenizerSha256;
    private final String configuredDiscoveryAlgorithmVersion;

    public EvaluationRetrievalEngine(EmbeddingProvider embeddingProvider,
            @Value("${ugnay.discovery.model-sha256:}") String configuredModelSha256,
            @Value("${ugnay.discovery.tokenizer-sha256:}") String configuredTokenizerSha256,
            @Value("${ugnay.discovery.algorithm-version:UNAVAILABLE}") String configuredDiscoveryAlgorithmVersion) {
        this.embeddingProvider = embeddingProvider;
        this.configuredModelSha256 = configuredEvidence(configuredModelSha256);
        this.configuredTokenizerSha256 = configuredEvidence(configuredTokenizerSha256);
        this.configuredDiscoveryAlgorithmVersion = configuredEvidence(configuredDiscoveryAlgorithmVersion);
    }

    Map<String, Object> semanticConfigurationManifest() {
        return Map.of(
                "modelVersion", configuredEvidence(embeddingProvider.name()),
                "modelSha256", configuredModelSha256,
                "tokenizerSha256", configuredTokenizerSha256,
                "discoveryConfigurationVersion", configuredDiscoveryAlgorithmVersion,
                "execution", "LOCAL_ONLY",
                "remoteCalls", false);
    }

    Map<String, Object> semanticProviderManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>(semanticConfigurationManifest());
        manifest.put("name", configuredEvidence(embeddingProvider.name()));
        manifest.put("configured", embeddingProvider.available());
        manifest.put("availabilityReason", embeddingProvider.availabilityReason() == null
                ? "No availability reason was supplied." : embeddingProvider.availabilityReason());
        return Map.copyOf(manifest);
    }

    private static String configuredEvidence(String value) {
        if (value == null || value.isBlank()) return "UNAVAILABLE";
        return value.trim();
    }

    public record StudyProfile(UUID id, String combinedText, Map<String, String> fields) {
        public StudyProfile {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record QueryProfile(UUID id, String combinedText, Map<String, String> fields) {
        public QueryProfile {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record RankedHit(UUID studyId, int rank, double score) {}
    public record ResourceSample(String phase, int order, Double wallMillis, Long processCpuNanos,
                                 long heapUsedBytes, long heapCommittedBytes) {}
    public record Outcome(Algorithm algorithm, RunStatus status, String unavailableReason, long indexBuildMillis,
                          double latencyP50Millis, double latencyP95Millis,
                          Map<UUID, List<RankedHit>> rankings, List<ResourceSample> resources) {}

    public Outcome evaluate(Algorithm algorithm, List<QueryProfile> queries, List<StudyProfile> studies, int repetitions) {
        if (queries == null || queries.isEmpty()) throw new IllegalArgumentException("A frozen evaluation requires at least one query.");
        if (studies == null || studies.isEmpty()) throw new IllegalArgumentException("A frozen evaluation requires at least one corpus study.");
        int safeRepetitions = Math.max(1, Math.min(repetitions, 20));

        List<ResourceSample> resources = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        resources.add(new ResourceSample("BEFORE", 0, null, null,
                runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory()));
        long indexCpuStart = processCpuTime();
        long indexStart = System.nanoTime();
        TermStatistics statistics = TermStatistics.from(studies.stream().map(study -> tokens(study.combinedText())).toList());
        boolean semanticConfigured = embeddingProvider.available();
        Map<String, Optional<double[]>> documentEmbeddings = new HashMap<>();
        if (semanticConfigured && (algorithm == Algorithm.SEMANTIC_E5 || algorithm == Algorithm.HYBRID)) {
            for (StudyProfile study : studies) {
                embedPassage(study.combinedText(), documentEmbeddings);
                if (algorithm == Algorithm.HYBRID) {
                    study.fields().values().stream().filter(value -> value != null && !value.isBlank())
                            .forEach(value -> embedPassage(value, documentEmbeddings));
                }
            }
        }
        long indexMillis = Math.max(0, (System.nanoTime() - indexStart) / 1_000_000);
        long indexCpuEnd = processCpuTime();
        resources.add(new ResourceSample("INDEX_BUILD", 0, (double) indexMillis,
                indexCpuStart < 0 || indexCpuEnd < 0 ? null : Math.max(0, indexCpuEnd - indexCpuStart),
                runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory()));

        MutableCompleteness completeness = new MutableCompleteness(semanticConfigured);
        for (QueryProfile query : queries) rank(algorithm, query, studies, statistics, documentEmbeddings, completeness);

        List<Double> timings = new ArrayList<>();
        Map<UUID, List<RankedHit>> last = new LinkedHashMap<>();
        int sampleOrder = 0;
        for (int repetition = 0; repetition < safeRepetitions; repetition++) {
            for (QueryProfile query : queries) {
                long cpuBefore = processCpuTime();
                long started = System.nanoTime();
                List<RankedHit> ranking = rank(algorithm, query, studies, statistics, documentEmbeddings, completeness);
                long elapsed = System.nanoTime() - started;
                long cpuAfter = processCpuTime();
                double millis = elapsed / 1_000_000.0;
                timings.add(millis);
                last.put(query.id(), ranking);
                resources.add(new ResourceSample("QUERY", sampleOrder++, millis,
                        cpuBefore < 0 || cpuAfter < 0 ? null : Math.max(0, cpuAfter - cpuBefore),
                        runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory()));
            }
        }
        resources.add(new ResourceSample("AFTER", 0, null, null,
                runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory()));

        RunStatus status = RunStatus.COMPLETED;
        String reason = null;
        if ((algorithm == Algorithm.SEMANTIC_E5 || algorithm == Algorithm.HYBRID) && !completeness.complete) {
            reason = embeddingProvider.availabilityReason();
            if (reason == null || reason.isBlank()) reason = "One or more required local semantic embeddings were unavailable.";
            if (algorithm == Algorithm.SEMANTIC_E5) {
                status = RunStatus.UNAVAILABLE;
                last = Map.of();
            } else {
                status = RunStatus.PARTIAL;
            }
        }
        return new Outcome(algorithm, status, reason, indexMillis, percentile(timings, .50), percentile(timings, .95),
                Map.copyOf(last), List.copyOf(resources));
    }

    private List<RankedHit> rank(Algorithm algorithm, QueryProfile query, List<StudyProfile> studies,
                                 TermStatistics statistics, Map<String, Optional<double[]>> documentEmbeddings,
                                 MutableCompleteness completeness) {
        Map<String, Optional<double[]>> requestEmbeddings = new HashMap<>();
        List<Scored> scored = new ArrayList<>(studies.size());
        for (StudyProfile study : studies) {
            Score score = switch (algorithm) {
                case LEXICAL_KEYWORD -> new Score(lexicalCoverage(query.combinedText(), study.combinedText()), true);
                case TF_IDF -> new Score(statistics.cosine(tokens(query.combinedText()), tokens(study.combinedText())), true);
                case SEMANTIC_E5 -> semantic(query.combinedText(), study.combinedText(), requestEmbeddings,
                        documentEmbeddings, completeness.semanticConfigured);
                case HYBRID -> hybrid(query, study, statistics, requestEmbeddings, documentEmbeddings,
                        completeness.semanticConfigured);
            };
            completeness.complete &= score.semanticComplete();
            scored.add(new Scored(study.id(), score.value()));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(value -> value.studyId().toString()));
        List<RankedHit> result = new ArrayList<>();
        for (int index = 0; index < Math.min(RESULT_LIMIT, scored.size()); index++) {
            Scored value = scored.get(index);
            result.add(new RankedHit(value.studyId(), index + 1, round(value.score())));
        }
        return List.copyOf(result);
    }

    private Score hybrid(QueryProfile query, StudyProfile study, TermStatistics statistics,
                         Map<String, Optional<double[]>> requestEmbeddings,
                         Map<String, Optional<double[]>> documentEmbeddings, boolean semanticConfigured) {
        Weighted problem = weighted(List.of(
                field("title", "title", .15), field("problemStatement", "problemStatement", .30),
                field("objectives", "objectives", .25), field("technology", "keywords", .10),
                field("stakeholders", "stakeholders", .10), field("siteContext", "siteContext", .10)),
                query, study, statistics, requestEmbeddings, documentEmbeddings, semanticConfigured);
        Weighted solution = weighted(List.of(
                field("proposedSolution", "features", .35), field("methodology", "methodology", .25),
                field("dataSources", "dataSources", .15), field("technology", "technology", .15),
                field("intendedUsers", "intendedUsers", .10)), query, study, statistics,
                requestEmbeddings, documentEmbeddings, semanticConfigured);
        return new Score(problem.value() * .65 + solution.value() * .35,
                problem.semanticComplete() && solution.semanticComplete());
    }

    private Weighted weighted(List<FieldPair> fields, QueryProfile query, StudyProfile study,
                              TermStatistics statistics, Map<String, Optional<double[]>> requestEmbeddings,
                              Map<String, Optional<double[]>> documentEmbeddings, boolean semanticConfigured) {
        double value = 0;
        boolean complete = semanticConfigured;
        for (FieldPair field : fields) {
            String left = query.fields().getOrDefault(field.queryField(), "");
            String right = study.fields().getOrDefault(field.studyField(), "");
            if (left.isBlank() || right.isBlank()) continue;
            Score score = componentScore(left, right, statistics, requestEmbeddings, documentEmbeddings, semanticConfigured);
            value += score.value() * field.weight();
            complete &= score.semanticComplete();
        }
        return new Weighted(value, complete);
    }

    private Score componentScore(String left, String right, TermStatistics statistics,
                                 Map<String, Optional<double[]>> requestEmbeddings,
                                 Map<String, Optional<double[]>> documentEmbeddings, boolean semanticConfigured) {
        List<String> leftTerms = tokens(left);
        List<String> rightTerms = tokens(right);
        double tfIdf = statistics.cosine(leftTerms, rightTerms);
        double concept = jaccard(concepts(leftTerms), concepts(rightTerms));
        Score semantic = semantic(left, right, requestEmbeddings, documentEmbeddings, semanticConfigured);
        return new Score(semantic.value() * .50 + tfIdf * .35 + concept * .15, semantic.semanticComplete());
    }

    private Score semantic(String query, String passage, Map<String, Optional<double[]>> requestEmbeddings,
                           Map<String, Optional<double[]>> documentEmbeddings, boolean semanticConfigured) {
        if (!semanticConfigured) return new Score(0, false);
        Optional<double[]> left = requestEmbeddings.computeIfAbsent(query,
                ignored -> embeddingProvider.embed("query: " + query).map(double[]::clone));
        Optional<double[]> right = embedPassage(passage, documentEmbeddings);
        if (left.isEmpty() || right.isEmpty()) return new Score(0, false);
        return new Score(Math.max(0, cosine(left.orElseThrow(), right.orElseThrow())), true);
    }

    private Optional<double[]> embedPassage(String value, Map<String, Optional<double[]>> cache) {
        return cache.computeIfAbsent(value, ignored -> embeddingProvider.embed("passage: " + value).map(double[]::clone));
    }

    static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+"))
                .stream().filter(term -> !term.isBlank() && term.length() > 1 && !STOP_WORDS.contains(term)).toList();
    }

    private static double lexicalCoverage(String query, String document) {
        Set<String> queryTerms = new LinkedHashSet<>(tokens(query));
        if (queryTerms.isEmpty()) return 0;
        Set<String> documentTerms = new HashSet<>(tokens(document));
        return queryTerms.stream().filter(documentTerms::contains).count() / (double) queryTerms.size();
    }

    private static Set<String> concepts(List<String> terms) {
        return terms.stream().map(term -> CONCEPTS.getOrDefault(term, term)).collect(Collectors.toSet());
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return intersection.size() / (double) union.size();
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length || left.length == 0) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static long processCpuTime() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem
                ? operatingSystem.getProcessCpuTime() : -1;
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().sorted().toList();
        int index = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
        return round(sorted.get(index));
    }

    private static double round(double value) { return Math.round(value * 100_000_000.0) / 100_000_000.0; }
    private static FieldPair field(String query, String study, double weight) { return new FieldPair(query, study, weight); }

    private record Score(double value, boolean semanticComplete) {}
    private record Scored(UUID studyId, double score) {}
    private record Weighted(double value, boolean semanticComplete) {}
    private record FieldPair(String queryField, String studyField, double weight) {}
    private static final class MutableCompleteness {
        private final boolean semanticConfigured;
        private boolean complete;
        private MutableCompleteness(boolean configured) { this.semanticConfigured = configured; this.complete = configured; }
    }

    private record TermStatistics(int documentCount, Map<String, Integer> documentFrequency) {
        static TermStatistics from(List<List<String>> documents) {
            Map<String, Integer> frequency = new HashMap<>();
            for (List<String> document : documents) new HashSet<>(document).forEach(term -> frequency.merge(term, 1, Integer::sum));
            return new TermStatistics(documents.size(), Map.copyOf(frequency));
        }

        double cosine(List<String> left, List<String> right) {
            if (left.isEmpty() || right.isEmpty()) return 0;
            Map<String, Long> leftFrequency = left.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Map<String, Long> rightFrequency = right.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Set<String> vocabulary = new HashSet<>(leftFrequency.keySet());
            vocabulary.addAll(rightFrequency.keySet());
            double dot = 0, leftNorm = 0, rightNorm = 0;
            for (String term : vocabulary) {
                double idf = Math.log((documentCount + 1.0) / (documentFrequency.getOrDefault(term, 0) + 1.0)) + 1.0;
                double leftValue = leftFrequency.getOrDefault(term, 0L) * idf;
                double rightValue = rightFrequency.getOrDefault(term, 0L) * idf;
                dot += leftValue * rightValue;
                leftNorm += leftValue * leftValue;
                rightNorm += rightValue * rightValue;
            }
            return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
        }
    }
}
