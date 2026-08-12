package com.ugnay.platform.researchquery;

import com.ugnay.platform.discovery.EmbeddingProvider;
import com.ugnay.platform.researchquery.ResearchQueryAst.Comparator;
import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.Field;
import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import com.ugnay.platform.researchquery.ResearchQueryAst.NumberLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Predicate;
import com.ugnay.platform.researchquery.ResearchQueryAst.StringLiteral;
import com.ugnay.platform.researchquery.ResearchQueryRepository.PreparedPlan;
import com.ugnay.platform.researchquery.ResearchQueryRepository.StudyRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.EXECUTION;

/** Deterministic, explainable live-catalogue ranking used only by the RQL interpreter. */
@Component
public class ResearchQueryScorer {
    private static final int SEMANTIC_POOL_LIMIT = 250;
    private static final int MAX_CACHED_EMBEDDINGS = 2_048;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of",
            "on", "or", "that", "the", "to", "with", "ang", "ay", "ito", "iyon", "mga", "ng", "para",
            "sa", "si", "yung", "isang", "maging", "gamit", "mula");
    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("baha", "flood"), Map.entry("pagbaha", "flood"), Map.entry("flooding", "flood"),
            Map.entry("sakuna", "disaster"), Map.entry("kalamidad", "disaster"),
            Map.entry("magsasaka", "farmer"), Map.entry("farmers", "farmer"),
            Map.entry("presyo", "price"), Map.entry("halaga", "price"),
            Map.entry("ospital", "hospital"), Map.entry("pasyente", "patient"),
            Map.entry("basura", "waste"), Map.entry("recycling", "waste"),
            Map.entry("estudyante", "student"), Map.entry("students", "student"),
            Map.entry("paaralan", "school"), Map.entry("campus", "school"));
    private static final SourceSpan EXECUTION_SPAN = new SourceSpan(0, 0, 1, 1, 1, 1);

    private final EmbeddingProvider embeddings;
    private final Map<String, double[]> embeddingCache = new ConcurrentHashMap<>();

    public ResearchQueryScorer(EmbeddingProvider embeddings) {
        this.embeddings = embeddings;
    }

    public ScoreOutcome score(PreparedPlan prepared, Collection<StudyRecord> input, long deadlineNanos) {
        checkDeadline(deadlineNanos);
        List<StudyRecord> studies = input == null ? List.of() : List.copyOf(input);
        List<String> queryTerms = tokens(prepared.queryText());
        if (queryTerms.isEmpty()) {
            List<ScoredStudy> unassessed = studies.stream()
                    .filter(study -> !study.id().equals(prepared.sourceStudyId()))
                    .map(study -> new ScoredStudy(study, null, 0, 0, null, 0, List.of(),
                            List.of("No retrieval text was available; relevance remains UNASSESSED.")))
                    .filter(value -> matches(prepared.plan().filter(), value.study(), value.score()))
                    .toList();
            return new ScoreOutcome(unassessed, "UNASSESSED", embeddings.name(),
                    List.of(new QueryDiagnostic(EXECUTION, "EXEC_NO_RETRIEVAL_TEXT",
                            "Filters were executed, but relevance is UNASSESSED because the query has no research text.",
                            EXECUTION_SPAN, List.of())));
        }

        Map<UUIDKey, List<String>> documentTerms = new HashMap<>();
        for (StudyRecord study : studies) documentTerms.put(new UUIDKey(study.id()), tokens(study.profileText()));
        TermStatistics statistics = TermStatistics.from(documentTerms.values());
        Set<String> querySet = new LinkedHashSet<>(queryTerms);
        List<BaseScore> base = new ArrayList<>(studies.size());
        for (StudyRecord study : studies) {
            checkDeadline(deadlineNanos);
            List<String> terms = documentTerms.get(new UUIDKey(study.id()));
            double lexical = coverage(querySet, terms) * 100;
            double tfidf = statistics.cosine(queryTerms, terms) * 100;
            double concept = jaccard(concepts(queryTerms), concepts(terms)) * 100;
            List<String> matched = queryTerms.stream().filter(new LinkedHashSet<>(terms)::contains).distinct().limit(8).toList();
            base.add(new BaseScore(study, lexical, tfidf, concept, matched));
        }

        SemanticScores semantic = semanticScores(prepared, base, deadlineNanos);
        boolean needsSemantic = prepared.plan().algorithm() == ResearchQueryAst.Algorithm.SEMANTIC
                || prepared.plan().algorithm() == ResearchQueryAst.Algorithm.HYBRID;
        if (prepared.plan().algorithm() == ResearchQueryAst.Algorithm.SEMANTIC && !semantic.available()) {
            return new ScoreOutcome(List.of(), "UNAVAILABLE", embeddings.name(),
                    List.of(new QueryDiagnostic(EXECUTION, "EXEC_SEMANTIC_UNAVAILABLE",
                            safeReason(), EXECUTION_SPAN, List.of())));
        }

        List<ScoredStudy> scored = new ArrayList<>();
        for (BaseScore value : base) {
            if (value.study().id().equals(prepared.sourceStudyId())) continue;
            Double semanticScore = semantic.scores().get(value.study().id());
            Double finalScore = switch (prepared.plan().algorithm()) {
                case LEXICAL -> value.lexical();
                case TFIDF -> value.tfidf();
                case SEMANTIC -> semanticScore;
                case HYBRID -> round((semanticScore == null ? 0 : semanticScore) * .50
                        + value.tfidf() * .35 + value.concept() * .15);
            };
            List<String> explanations = explanations(prepared.plan().algorithm(), value, semanticScore, semantic.available());
            ScoredStudy candidate = new ScoredStudy(value.study(), finalScore, round(value.lexical()), round(value.tfidf()),
                    semanticScore == null ? null : round(semanticScore), round(value.concept()), value.matchedTerms(), explanations);
            if (matches(prepared.plan().filter(), candidate.study(), candidate.score())) scored.add(candidate);
        }

        List<QueryDiagnostic> diagnostics = new ArrayList<>();
        String status = "ASSESSED";
        if (needsSemantic && !semantic.available()) {
            status = "PARTIAL";
            diagnostics.add(new QueryDiagnostic(EXECUTION, "EXEC_SEMANTIC_UNAVAILABLE",
                    safeReason() + " Hybrid weights were not rescaled.", EXECUTION_SPAN, List.of()));
        } else if (needsSemantic && semantic.poolLimited()) {
            status = "PARTIAL";
            diagnostics.add(new QueryDiagnostic(EXECUTION, "EXEC_SEMANTIC_POOL_LIMIT",
                    "Semantic reranking was bounded to the 250 strongest TF-IDF candidates.", EXECUTION_SPAN, List.of()));
        }
        return new ScoreOutcome(scored, status, embeddings.name(), diagnostics);
    }

    private SemanticScores semanticScores(PreparedPlan prepared, List<BaseScore> base, long deadlineNanos) {
        if (prepared.plan().algorithm() != ResearchQueryAst.Algorithm.SEMANTIC
                && prepared.plan().algorithm() != ResearchQueryAst.Algorithm.HYBRID) {
            return new SemanticScores(Map.of(), false, false);
        }
        if (!embeddings.available()) return new SemanticScores(Map.of(), false, false);
        checkDeadline(deadlineNanos);
        Optional<double[]> queryVector = embeddings.embed("query: " + prepared.queryText());
        if (queryVector.isEmpty()) return new SemanticScores(Map.of(), false, false);
        List<BaseScore> pool = base.stream()
                .sorted(java.util.Comparator.comparingDouble(BaseScore::tfidf).reversed()
                        .thenComparing(value -> value.study().id().toString()))
                .limit(SEMANTIC_POOL_LIMIT).toList();
        Map<java.util.UUID, Double> scores = new HashMap<>();
        for (BaseScore candidate : pool) {
            checkDeadline(deadlineNanos);
            Optional<double[]> vector = studyEmbedding(candidate.study());
            vector.ifPresent(value -> scores.put(candidate.study().id(), Math.max(0, cosine(queryVector.orElseThrow(), value)) * 100));
        }
        return new SemanticScores(Map.copyOf(scores), !scores.isEmpty(), base.size() > pool.size());
    }

    private Optional<double[]> studyEmbedding(StudyRecord study) {
        String profile = study.profileText();
        String key = digest(profile);
        double[] cached = embeddingCache.get(key);
        if (cached != null) return Optional.of(cached.clone());
        Optional<double[]> calculated = embeddings.embed("passage: " + profile).map(double[]::clone);
        calculated.ifPresent(vector -> {
            if (embeddingCache.size() >= MAX_CACHED_EMBEDDINGS) embeddingCache.clear();
            embeddingCache.put(key, vector.clone());
        });
        return calculated;
    }

    private String safeReason() {
        String reason = embeddings.availabilityReason();
        return reason == null || reason.isBlank()
                ? "The local semantic model is unavailable; semantic similarity was not assessed."
                : reason;
    }

    private static List<String> explanations(ResearchQueryAst.Algorithm algorithm, BaseScore value,
                                             Double semantic, boolean semanticAvailable) {
        return switch (algorithm) {
            case LEXICAL -> List.of("Score is normalized query-token coverage.");
            case TFIDF -> List.of("Score is corpus-aware TF-IDF cosine similarity.");
            case SEMANTIC -> List.of(semantic == null
                    ? "Semantic score is unavailable for this bounded candidate."
                    : "Score is local multilingual E5 embedding cosine similarity.");
            case HYBRID -> List.of(
                    "Hybrid = 50% semantic + 35% TF-IDF + 15% controlled-concept overlap.",
                    semanticAvailable && semantic != null
                            ? "Local semantic evidence was available."
                            : "Semantic evidence was unavailable for this candidate; weights were not rescaled.");
        };
    }

    static boolean matches(Expression expression, StudyRecord study, Double similarity) {
        if (expression == null) return true;
        if (expression instanceof Group group) return matches(group.expression(), study, similarity);
        if (expression instanceof Logical logical) {
            boolean left = matches(logical.left(), study, similarity);
            return logical.operator() == ResearchQueryAst.BooleanOperator.AND
                    ? left && matches(logical.right(), study, similarity)
                    : left || matches(logical.right(), study, similarity);
        }
        Predicate predicate = (Predicate) expression;
        if (predicate.field() == Field.YEAR) {
            return study.year() != null && compare(BigDecimal.valueOf(study.year()),
                    ((NumberLiteral) predicate.value()).value(), predicate.comparator());
        }
        if (predicate.field() == Field.SIMILARITY) {
            return similarity != null && compare(BigDecimal.valueOf(similarity),
                    ((NumberLiteral) predicate.value()).value(), predicate.comparator());
        }
        String expected = normalize(((StringLiteral) predicate.value()).value());
        List<String> actual = stringValues(predicate.field(), study).stream().map(ResearchQueryScorer::normalize)
                .filter(value -> !value.isBlank()).toList();
        if (actual.isEmpty()) return false;
        return switch (predicate.comparator()) {
            case EQUAL -> actual.stream().anyMatch(expected::equals);
            case NOT_EQUAL -> actual.stream().noneMatch(expected::equals);
            case CONTAINS -> actual.stream().anyMatch(value -> value.contains(expected));
            default -> false;
        };
    }

    private static List<String> stringValues(Field field, StudyRecord study) {
        return switch (field) {
            case TOPIC -> combine(List.of(safe(study.title())), study.keywords(), study.researchAreas());
            case TITLE -> List.of(safe(study.title()));
            case KEYWORD -> study.keywords();
            case DEPARTMENT -> List.of(safe(study.departmentCode()), safe(study.departmentName()));
            case METHODOLOGY -> List.of(safe(study.methodology()));
            case RESEARCH_AREA -> study.researchAreas();
            case STATUS -> List.of(safe(study.lifecycle()));
            case YEAR, SIMILARITY -> List.of();
        };
    }

    @SafeVarargs
    private static List<String> combine(List<String>... sources) {
        List<String> values = new ArrayList<>();
        for (List<String> source : sources) values.addAll(source);
        return values;
    }

    private static boolean compare(BigDecimal left, BigDecimal right, Comparator comparator) {
        int comparison = left.compareTo(right);
        return switch (comparator) {
            case EQUAL -> comparison == 0;
            case NOT_EQUAL -> comparison != 0;
            case GREATER -> comparison > 0;
            case GREATER_EQUAL -> comparison >= 0;
            case LESS -> comparison < 0;
            case LESS_EQUAL -> comparison <= 0;
            case CONTAINS -> false;
        };
    }

    static List<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return List.of();
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}+#.-]+"))
                .filter(term -> term.length() > 1).filter(term -> !STOP_WORDS.contains(term)).toList();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private static Set<String> concepts(List<String> terms) {
        return terms.stream().map(term -> CONCEPTS.getOrDefault(term, term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static double coverage(Set<String> query, List<String> document) {
        if (query.isEmpty()) return 0;
        Set<String> found = new HashSet<>(document);
        long matches = query.stream().filter(found::contains).count();
        return (double) matches / query.size();
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length == 0 || left.length != right.length) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void checkDeadline(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
            throw new ExecutionDeadlineExceededException();
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }

    public record ScoredStudy(StudyRecord study, Double score, double lexicalScore, double tfIdfScore,
                              Double semanticScore, double conceptScore, List<String> matchedTerms,
                              List<String> explanations) {
        public ScoredStudy {
            matchedTerms = List.copyOf(matchedTerms);
            explanations = List.copyOf(explanations);
            score = score == null ? null : round(score);
        }
    }

    public record ScoreOutcome(List<ScoredStudy> studies, String assessmentStatus, String semanticProvider,
                               List<QueryDiagnostic> diagnostics) {
        public ScoreOutcome {
            studies = List.copyOf(studies);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public static final class ExecutionDeadlineExceededException extends RuntimeException {
        public ExecutionDeadlineExceededException() { super("The research query exceeded its execution deadline."); }
    }

    private record BaseScore(StudyRecord study, double lexical, double tfidf, double concept,
                             List<String> matchedTerms) {}
    private record SemanticScores(Map<java.util.UUID, Double> scores, boolean available, boolean poolLimited) {}
    private record UUIDKey(java.util.UUID value) {}

    private record TermStatistics(int documentCount, Map<String, Integer> documentFrequency) {
        static TermStatistics from(Collection<List<String>> documents) {
            Map<String, Integer> frequency = new HashMap<>();
            for (List<String> document : documents) {
                new HashSet<>(document).forEach(term -> frequency.merge(term, 1, Integer::sum));
            }
            return new TermStatistics(documents.size(), Map.copyOf(frequency));
        }

        double cosine(List<String> left, List<String> right) {
            if (left.isEmpty() || right.isEmpty()) return 0;
            Map<String, Long> leftFrequency = left.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Map<String, Long> rightFrequency = right.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Set<String> vocabulary = new HashSet<>(leftFrequency.keySet());
            vocabulary.addAll(rightFrequency.keySet());
            double dot = 0, leftNorm = 0, rightNorm = 0;
            for (String term : vocabulary) {
                double idf = Math.log((documentCount + 1.0)
                        / (documentFrequency.getOrDefault(term, 0) + 1.0)) + 1.0;
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
