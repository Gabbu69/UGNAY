package com.ugnay.platform.discovery;

import com.ugnay.platform.shared.PlatformModels.CandidateEvidence;
import com.ugnay.platform.shared.PlatformModels.ComponentScore;
import com.ugnay.platform.shared.PlatformModels.DiscoveryCandidate;
import com.ugnay.platform.shared.PlatformModels.Proposal;
import com.ugnay.platform.shared.PlatformModels.Study;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Two-stage, explainable discovery engine.
 *
 * <p>Study profiles and corpus document frequencies are held in a bounded
 * in-memory index. Candidate generation unions exact matches, the 50 strongest
 * corpus-aware lexical profiles, and the 50 strongest cached semantic profile
 * cosines. Detailed field scoring is then limited to that union and only the
 * best ten results are returned. The pilot index is rebuilt synchronously when
 * the supplied corpus changes; persisted/offline index construction is outside
 * this implementation.</p>
 */
@Service
public final class SimilarityEngine {
    static final int CANDIDATE_LIMIT = 50;
    static final int RESULT_LIMIT = 10;

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
    private volatile CorpusIndex corpusIndex = CorpusIndex.empty();
    private volatile RetrievalDiagnostics diagnostics = RetrievalDiagnostics.empty();

    public SimilarityEngine(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public List<DiscoveryCandidate> rank(Proposal proposal, Collection<Study> studies) {
        return rank(proposal, studies, true);
    }

    /** Used only while constructing the synthetic seed so application startup never loads the local model. */
    public List<DiscoveryCandidate> rankLexical(Proposal proposal, Collection<Study> studies) {
        return rank(proposal, studies, false);
    }

    private List<DiscoveryCandidate> rank(Proposal proposal, Collection<Study> studies, boolean allowSemantic) {
        CorpusIndex index = ensureIndex(studies, allowSemantic);
        String queryProfile = proposalProfile(proposal);
        List<String> queryTerms = tokens(queryProfile);
        RequestEmbeddingCache requestEmbeddings = new RequestEmbeddingCache(index.semanticEnabled(), embeddingProvider);

        List<RankedProfile> lexical = index.profiles().values().stream()
                .map(profile -> new RankedProfile(profile, index.statistics().cosine(queryTerms, profile.terms())))
                .filter(value -> value.score() > 0)
                .sorted(rankedComparator())
                .limit(CANDIDATE_LIMIT)
                .toList();

        Optional<double[]> queryVector = requestEmbeddings.embed(queryProfile);
        List<RankedProfile> semantic = queryVector.isEmpty() ? List.of() : index.profiles().values().stream()
                .filter(profile -> profile.profileEmbedding().isPresent())
                .map(profile -> new RankedProfile(profile,
                        Math.max(0, cosine(queryVector.orElseThrow(), profile.profileEmbedding().orElseThrow()))))
                .filter(value -> value.score() > 0)
                .sorted(rankedComparator())
                .limit(CANDIDATE_LIMIT)
                .toList();

        List<StudyProfile> exact = index.profiles().values().stream()
                .filter(profile -> exactMatch(proposal, profile.study()))
                .sorted(Comparator.comparing(profile -> profile.study().id().toString()))
                .toList();

        Map<UUID, StudyProfile> union = new LinkedHashMap<>();
        exact.forEach(profile -> union.put(profile.study().id(), profile));
        lexical.forEach(value -> union.putIfAbsent(value.profile().study().id(), value.profile()));
        semantic.forEach(value -> union.putIfAbsent(value.profile().study().id(), value.profile()));

        List<ScoredStudy> scored = union.values().stream()
                .map(profile -> score(proposal, profile, index.statistics(), requestEmbeddings))
                .sorted(Comparator.comparing(ScoredStudy::exactMatch).reversed()
                        .thenComparing(Comparator.comparingDouble(ScoredStudy::rankingScore).reversed())
                        .thenComparing(value -> value.study().id().toString()))
                .limit(RESULT_LIMIT)
                .toList();

        diagnostics = new RetrievalDiagnostics(index.profiles().size(), lexical.size(), semantic.size(), exact.size(),
                union.size(), union.size(), scored.size(),
                lexical.stream().map(value -> value.profile().study().id()).collect(Collectors.toUnmodifiableSet()),
                semantic.stream().map(value -> value.profile().study().id()).collect(Collectors.toUnmodifiableSet()),
                queryVector.isPresent());

        List<DiscoveryCandidate> ranked = new ArrayList<>();
        for (int position = 0; position < scored.size(); position++) {
            ScoredStudy value = scored.get(position);
            ranked.add(new DiscoveryCandidate(position + 1, value.study().id(), value.study().title(),
                    round(value.problemScore()), round(value.objectiveScore()), round(value.solutionScore()),
                    round(value.confidence()), band(value.rankingScore()), value.exactMatch(), value.evidence()));
        }
        return ranked;
    }

    public String providerName() {
        return embeddingProvider.name();
    }

    public String providerExplanation() {
        return embeddingProvider.availabilityReason();
    }

    public boolean semanticAvailable() {
        return embeddingProvider.available();
    }

    public boolean lastRunUsedSemanticEvidence() {
        return diagnostics.queryVectorPresent();
    }

    /** Explicit warm-up hook for deployments that can load the catalogue before the first request. */
    public void warmIndex(Collection<Study> studies) {
        ensureIndex(studies, true);
    }

    RetrievalDiagnostics lastDiagnostics() {
        return diagnostics;
    }

    public double objectiveOverlap(List<String> proposed, List<String> prior) {
        RequestEmbeddingCache request = new RequestEmbeddingCache(true, embeddingProvider);
        return objectiveOverlap(proposed, prior, corpusIndex.statistics(), request, request::embed);
    }

    /** Percentage of proposed objectives that cannot be paired one-to-one with a related prior objective. */
    public double objectiveNoveltyPercentage(List<String> proposed, List<String> prior) {
        if (proposed == null || proposed.isEmpty()) return 0;
        if (prior == null || prior.isEmpty()) return 100;
        int rightSize = Math.min(prior.size(), 20);
        RequestEmbeddingCache request = new RequestEmbeddingCache(true, embeddingProvider);
        List<List<Integer>> related = new ArrayList<>();
        for (String objective : proposed) {
            List<Integer> candidates = new ArrayList<>();
            for (int index = 0; index < rightSize; index++) {
                if (scoreField(objective, prior.get(index), "objective-novelty", corpusIndex.statistics(),
                        request::embed, request::embed).score() >= 45) candidates.add(index);
            }
            related.add(candidates);
        }
        int[] pairedProposal = new int[rightSize];
        Arrays.fill(pairedProposal, -1);
        int matched = 0;
        for (int proposalIndex = 0; proposalIndex < related.size(); proposalIndex++) {
            if (augmentObjectiveMatch(proposalIndex, related, pairedProposal, new boolean[rightSize])) matched++;
        }
        return round((proposed.size() - matched) * 100.0 / proposed.size());
    }

    public double fieldScore(String left, String right) {
        RequestEmbeddingCache request = new RequestEmbeddingCache(true, embeddingProvider);
        return round(scoreField(left, right, "text", corpusIndex.statistics(), request::embed, request::embed).score());
    }

    private ScoredStudy score(Proposal proposal, StudyProfile profile, TermStatistics statistics,
                              RequestEmbeddingCache requestEmbeddings) {
        Study study = profile.study();
        List<CandidateEvidence> evidence = new ArrayList<>();
        WeightedResult problem = weightedFields(evidence, List.of(
                field("title", proposal.title(), study.title(), .15),
                field("problemStatement", proposal.problemStatement(), study.problemStatement(), .30),
                field("objectives", join(proposal.objectives()), join(study.objectives()), .25),
                field("domainKeywords", proposal.technology(), join(study.keywords()), .10),
                field("stakeholders", proposal.stakeholder(), study.stakeholders(), .10),
                field("siteContext", proposal.siteContext(), study.siteContext(), .10)),
                statistics, requestEmbeddings, profile);

        WeightedResult solution = weightedFields(evidence, List.of(
                field("features", proposal.proposedSolution(), study.features(), .35),
                field("methodology", proposal.methodology(), study.methodology(), .25),
                field("dataSources", proposal.dataSources(), study.dataSources(), .15),
                field("technology", proposal.technology(), study.technology(), .15),
                field("intendedUsers", proposal.intendedUsers(), study.intendedUsers(), .10)),
                statistics, requestEmbeddings, profile);

        double objective = objectiveOverlap(proposal.objectives(), study.objectives(), statistics,
                requestEmbeddings, profile::embedField);
        double confidence = (problem.comparableWeight() * .65 + solution.comparableWeight() * .35) * 100;
        boolean exact = exactMatch(proposal, study);
        double ranking = problem.score() * .65 + solution.score() * .35;
        return new ScoredStudy(study, problem.score(), objective, solution.score(), confidence, ranking, exact,
                List.copyOf(evidence));
    }

    private WeightedResult weightedFields(List<CandidateEvidence> evidence, List<FieldInput> fields,
                                          TermStatistics statistics, RequestEmbeddingCache requestEmbeddings,
                                          StudyProfile profile) {
        double score = 0;
        double comparable = 0;
        for (FieldInput field : fields) {
            if (isBlank(field.left()) || isBlank(field.right())) continue;
            FieldResult result = scoreField(field.left(), field.right(), field.name(), statistics,
                    requestEmbeddings::embed, profile::embedField);
            score += result.score() * field.weight();
            comparable += field.weight();
            evidence.add(new CandidateEvidence(field.name(), excerpt(field.left()), excerpt(field.right()), result.components()));
        }
        return new WeightedResult(score, comparable);
    }

    private double objectiveOverlap(List<String> proposed, List<String> prior, TermStatistics statistics,
                                    RequestEmbeddingCache requestEmbeddings,
                                    Function<String, Optional<double[]>> studyEmbedding) {
        if (proposed == null || prior == null || proposed.isEmpty() || prior.isEmpty()) return 0;
        int rightSize = Math.min(prior.size(), 20);
        double[][] matrix = new double[proposed.size()][rightSize];
        for (int row = 0; row < proposed.size(); row++) {
            for (int column = 0; column < rightSize; column++) {
                matrix[row][column] = scoreField(proposed.get(row), prior.get(column), "objective", statistics,
                        requestEmbeddings::embed, studyEmbedding).score();
            }
        }
        double maximum = match(matrix, 0, 0L, new HashMap<>());
        return round(maximum / Math.max(proposed.size(), prior.size()));
    }

    private FieldResult scoreField(String left, String right, String label, TermStatistics statistics,
                                   Function<String, Optional<double[]>> leftEmbedding,
                                   Function<String, Optional<double[]>> rightEmbedding) {
        List<String> leftTerms = tokens(left);
        List<String> rightTerms = tokens(right);
        double lexical = statistics.cosine(leftTerms, rightTerms) * 100;
        double concept = jaccard(canonicalConcepts(leftTerms), canonicalConcepts(rightTerms)) * 100;
        Optional<double[]> leftVector = leftEmbedding.apply(left);
        Optional<double[]> rightVector = rightEmbedding.apply(right);
        double semantic = leftVector.isPresent() && rightVector.isPresent()
                ? Math.max(0, cosine(leftVector.orElseThrow(), rightVector.orElseThrow())) * 100 : 0;
        double result = semantic * .50 + lexical * .35 + concept * .15;
        List<String> common = leftTerms.stream().filter(new LinkedHashSet<>(rightTerms)::contains).distinct().limit(8).toList();
        List<ComponentScore> components = List.of(
                component("semanticCosine", semantic, .50, leftVector.isPresent() && rightVector.isPresent()
                        ? "Local embedding cosine similarity."
                        : "Unavailable; its 50% contribution remains zero and weights are not rescaled.", common),
                component("tfIdfCosine", lexical, .35,
                        "Cosine similarity using document frequencies frozen from the active study-profile corpus.", common),
                component("controlledConceptJaccard", concept, .15,
                        "Jaccard overlap after the curated bilingual concept map.", common));
        return new FieldResult(result, components);
    }

    private synchronized CorpusIndex ensureIndex(Collection<Study> studies, boolean allowSemantic) {
        List<Study> ordered = studies == null ? List.of() : studies.stream()
                .sorted(Comparator.comparing(study -> study.id().toString())).toList();
        boolean semanticEnabled = allowSemantic && embeddingProvider.available();
        Map<UUID, String> fingerprints = ordered.stream().collect(Collectors.toMap(Study::id,
                study -> digest(studyProfile(study)), (left, right) -> right, LinkedHashMap::new));
        CorpusIndex existing = corpusIndex;
        if (existing.semanticEnabled() == semanticEnabled && existing.fingerprints().equals(fingerprints)) return existing;

        Map<UUID, StudyProfile> profiles = new LinkedHashMap<>();
        for (Study study : ordered) {
            String fingerprint = fingerprints.get(study.id());
            StudyProfile reusable = existing.profiles().get(study.id());
            if (reusable != null && reusable.fingerprint().equals(fingerprint)
                    && existing.semanticEnabled() == semanticEnabled) {
                profiles.put(study.id(), reusable);
                continue;
            }
            String profileText = studyProfile(study);
            Optional<double[]> embedding = semanticEnabled
                    ? embeddingProvider.embed(e5Query(profileText)).map(double[]::clone) : Optional.empty();
            profiles.put(study.id(), new StudyProfile(study, fingerprint, tokens(profileText), embedding,
                    semanticEnabled, embeddingProvider));
        }
        CorpusIndex rebuilt = new CorpusIndex(Map.copyOf(profiles), Map.copyOf(fingerprints),
                TermStatistics.from(profiles.values().stream().map(StudyProfile::terms).toList()), semanticEnabled);
        corpusIndex = rebuilt;
        return rebuilt;
    }

    private static Comparator<RankedProfile> rankedComparator() {
        return Comparator.comparingDouble(RankedProfile::score).reversed()
                .thenComparing(value -> value.profile().study().id().toString());
    }

    private static boolean exactMatch(Proposal proposal, Study study) {
        String proposalTitle = normalize(proposal.title());
        return proposalTitle.equals(normalize(study.title()))
                || proposalTitle.equals(normalize(study.institutionalCode()));
    }

    private static ComponentScore component(String name, double raw, double weight, String explanation, List<String> terms) {
        return new ComponentScore(name, round(raw), weight, round(raw * weight), explanation, terms);
    }

    private static double match(double[][] matrix, int row, long used, Map<Long, Double> memo) {
        if (row >= matrix.length) return 0;
        long key = (((long) row) << 32) ^ used;
        Double existing = memo.get(key);
        if (existing != null) return existing;
        double best = match(matrix, row + 1, used, memo);
        for (int column = 0; column < matrix[row].length; column++) {
            long bit = 1L << column;
            if ((used & bit) == 0) best = Math.max(best, matrix[row][column] + match(matrix, row + 1, used | bit, memo));
        }
        memo.put(key, best);
        return best;
    }

    private static boolean augmentObjectiveMatch(int proposalIndex, List<List<Integer>> related,
                                                 int[] pairedProposal, boolean[] visited) {
        for (int priorIndex : related.get(proposalIndex)) {
            if (visited[priorIndex]) continue;
            visited[priorIndex] = true;
            if (pairedProposal[priorIndex] == -1
                    || augmentObjectiveMatch(pairedProposal[priorIndex], related, pairedProposal, visited)) {
                pairedProposal[priorIndex] = proposalIndex;
                return true;
            }
        }
        return false;
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

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> canonicalConcepts(List<String> terms) {
        return terms.stream().map(term -> CONCEPTS.getOrDefault(term, term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static List<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return List.of();
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}+#.-]+"))
                .filter(term -> term.length() > 1)
                .filter(term -> !STOP_WORDS.contains(term))
                .map(term -> CONCEPTS.getOrDefault(term, term))
                .toList();
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private static String band(double score) {
        if (score < 45) return "WEAK";
        if (score < 65) return "RELATED";
        if (score < 80) return "STRONG_OVERLAP";
        return "VERY_STRONG_OVERLAP";
    }

    private static String proposalProfile(Proposal proposal) {
        return joinText(proposal.title(), proposal.problemStatement(), join(proposal.objectives()), proposal.technology(),
                proposal.stakeholder(), proposal.affectedUsers(), proposal.siteContext(), proposal.proposedSolution(),
                proposal.methodology(), proposal.dataSources(), proposal.intendedUsers());
    }

    private static String studyProfile(Study study) {
        return joinText(study.institutionalCode(), study.title(), study.abstractText(), study.problemStatement(),
                join(study.objectives()), join(study.keywords()), study.stakeholders(), study.siteContext(), study.features(),
                study.methodology(), study.dataSources(), study.technology(), study.intendedUsers());
    }

    private static String joinText(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(" "));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String join(List<String> values) { return values == null ? "" : String.join(" ", values); }
    private static String safe(String value) { return value == null ? "" : value; }
    static String e5Query(String value) {
        String safe = safe(value).trim();
        return safe.startsWith("query: ") ? safe : "query: " + safe;
    }
    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static String excerpt(String text) { return text.length() <= 220 ? text : text.substring(0, 217) + "..."; }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static FieldInput field(String name, String left, String right, double weight) {
        return new FieldInput(name, left, right, weight);
    }

    record RetrievalDiagnostics(int corpusSize, int lexicalPoolSize, int semanticPoolSize, int exactPoolSize,
                                int unionSize, int detailedRerankCount, int returnedCount,
                                Set<UUID> lexicalCandidateIds, Set<UUID> semanticCandidateIds,
                                boolean queryVectorPresent) {
        static RetrievalDiagnostics empty() {
            return new RetrievalDiagnostics(0, 0, 0, 0, 0, 0, 0, Set.of(), Set.of(), false);
        }
    }

    private record FieldInput(String name, String left, String right, double weight) {}
    private record FieldResult(double score, List<ComponentScore> components) {}
    private record WeightedResult(double score, double comparableWeight) {}
    private record RankedProfile(StudyProfile profile, double score) {}
    private record ScoredStudy(Study study, double problemScore, double objectiveScore, double solutionScore,
                               double confidence, double rankingScore, boolean exactMatch,
                               List<CandidateEvidence> evidence) {}
    private record CorpusIndex(Map<UUID, StudyProfile> profiles, Map<UUID, String> fingerprints,
                               TermStatistics statistics, boolean semanticEnabled) {
        static CorpusIndex empty() { return new CorpusIndex(Map.of(), Map.of(), TermStatistics.empty(), false); }
    }

    private static final class StudyProfile {
        private final Study study;
        private final String fingerprint;
        private final List<String> terms;
        private final Optional<double[]> profileEmbedding;
        private final boolean semanticEnabled;
        private final EmbeddingProvider provider;
        private final Map<String, Optional<double[]>> fieldEmbeddings = new ConcurrentHashMap<>();

        private StudyProfile(Study study, String fingerprint, List<String> terms, Optional<double[]> profileEmbedding,
                             boolean semanticEnabled, EmbeddingProvider provider) {
            this.study = study;
            this.fingerprint = fingerprint;
            this.terms = List.copyOf(terms);
            this.profileEmbedding = profileEmbedding.map(double[]::clone);
            this.semanticEnabled = semanticEnabled;
            this.provider = provider;
        }

        Study study() { return study; }
        String fingerprint() { return fingerprint; }
        List<String> terms() { return terms; }
        Optional<double[]> profileEmbedding() { return profileEmbedding.map(double[]::clone); }

        Optional<double[]> embedField(String text) {
            if (!semanticEnabled || text == null || text.isBlank()) return Optional.empty();
            return fieldEmbeddings.computeIfAbsent(normalize(text), ignored -> provider.embed(e5Query(text)).map(double[]::clone))
                    .map(double[]::clone);
        }
    }

    private static final class RequestEmbeddingCache {
        private final boolean semanticEnabled;
        private final EmbeddingProvider provider;
        private final Map<String, Optional<double[]>> embeddings = new HashMap<>();

        private RequestEmbeddingCache(boolean semanticEnabled, EmbeddingProvider provider) {
            this.semanticEnabled = semanticEnabled;
            this.provider = provider;
        }

        Optional<double[]> embed(String text) {
            if (!semanticEnabled || text == null || text.isBlank()) return Optional.empty();
            return embeddings.computeIfAbsent(normalize(text), ignored -> provider.embed(e5Query(text)).map(double[]::clone))
                    .map(double[]::clone);
        }
    }

    private record TermStatistics(int documentCount, Map<String, Integer> documentFrequency) {
        static TermStatistics empty() { return new TermStatistics(0, Map.of()); }

        static TermStatistics from(List<List<String>> documents) {
            Map<String, Integer> frequency = new HashMap<>();
            for (List<String> document : documents) {
                new HashSet<>(document).forEach(term -> frequency.merge(term, 1, Integer::sum));
            }
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
