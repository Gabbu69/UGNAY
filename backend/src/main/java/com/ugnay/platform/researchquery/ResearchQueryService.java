package com.ugnay.platform.researchquery;

import com.ugnay.platform.researchquery.ResearchQueryAst.Expression;
import com.ugnay.platform.researchquery.ResearchQueryAst.Group;
import com.ugnay.platform.researchquery.ResearchQueryAst.Logical;
import com.ugnay.platform.researchquery.ResearchQueryAst.NumberLiteral;
import com.ugnay.platform.researchquery.ResearchQueryAst.Predicate;
import com.ugnay.platform.researchquery.ResearchQueryAst.Query;
import com.ugnay.platform.researchquery.ResearchQueryAst.StringLiteral;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ActionView;
import com.ugnay.platform.researchquery.ResearchQueryContracts.AstNode;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ExecuteRequest;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ExecuteResponse;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ResultView;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ScoreComponents;
import com.ugnay.platform.researchquery.ResearchQueryContracts.TokenView;
import com.ugnay.platform.researchquery.ResearchQueryContracts.ValidationView;
import com.ugnay.platform.researchquery.ResearchQueryContracts.WarehouseReference;
import com.ugnay.platform.researchquery.ResearchQueryRepository.CandidateLoad;
import com.ugnay.platform.researchquery.ResearchQueryRepository.Preparation;
import com.ugnay.platform.researchquery.ResearchQueryRepository.PreparedPlan;
import com.ugnay.platform.researchquery.ResearchQueryScorer.ExecutionDeadlineExceededException;
import com.ugnay.platform.researchquery.ResearchQueryScorer.ScoreOutcome;
import com.ugnay.platform.researchquery.ResearchQueryScorer.ScoredStudy;
import com.ugnay.platform.shared.JdbcAuditService;
import jakarta.annotation.PreDestroy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.ugnay.platform.researchquery.QueryDiagnostic.Stage.EXECUTION;

@Service
public class ResearchQueryService {
    private static final SourceSpan EXECUTION_SPAN = new SourceSpan(0, 0, 1, 1, 1, 1);
    private static final WarehouseReference NO_WAREHOUSE = new WarehouseReference("UNAVAILABLE", null, null,
            "No immutable warehouse snapshot is currently published; execution used the authoritative live catalogue.");

    private final ResearchQueryLexer lexer = new ResearchQueryLexer();
    private final ResearchQueryParser parser = new ResearchQueryParser();
    private final ResearchQuerySemanticValidator validator = new ResearchQuerySemanticValidator();
    private final ResearchQueryRepository repository;
    private final ResearchQueryScorer scorer;
    private final JdbcAuditService audit;
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().name("ugnay-rql-", 0).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public ResearchQueryService(ResearchQueryRepository repository, ResearchQueryScorer scorer, JdbcAuditService audit) {
        this.repository = repository;
        this.scorer = scorer;
        this.audit = audit;
    }

    public ExecuteResponse execute(ExecuteRequest request, Authentication authentication) {
        long started = System.nanoTime();
        ExecuteRequest safeRequest = request == null ? new ExecuteRequest(null, false, null) : request;
        Future<ExecuteResponse> future;
        try {
            future = executor.submit(() -> executeInternal(safeRequest, authentication, started));
        } catch (RejectedExecutionException exception) {
            ExecuteResponse response = failed(safeRequest, "UNAVAILABLE", "EXEC_CAPACITY_LIMIT",
                    "The bounded research-query worker is currently at capacity; retry shortly.", started);
            appendAudit(safeRequest, authentication, response);
            return response;
        }
        ExecuteResponse response;
        try {
            response = future.get(ResearchQueryLanguage.EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            response = failed(safeRequest, "TIMED_OUT", "EXEC_TIMEOUT",
                    "The research query exceeded the five-second execution limit.", started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            response = failed(safeRequest, "FAILED", "EXEC_INTERRUPTED",
                    "The research query was interrupted safely.", started);
        } catch (ExecutionException exception) {
            future.cancel(true);
            String code = exception.getCause() instanceof ExecutionDeadlineExceededException
                    ? "EXEC_TIMEOUT" : "EXECUTION_FAILED";
            String message = "EXEC_TIMEOUT".equals(code)
                    ? "The research query exceeded the five-second execution limit."
                    : "The research query could not be executed safely.";
            response = failed(safeRequest, "EXEC_TIMEOUT".equals(code) ? "TIMED_OUT" : "FAILED",
                    code, message, started);
        }
        appendAudit(safeRequest, authentication, response);
        return response;
    }

    private ExecuteResponse executeInternal(ExecuteRequest request, Authentication authentication, long started) {
        var lexed = lexer.tokenize(request.source());
        List<TokenView> tokenTrace = request.wantsTrace() ? lexed.tokens().stream().map(ResearchQueryService::token).toList()
                : List.of();
        if (!lexed.valid()) return invalid(request, tokenTrace, null, lexed.diagnostics(), "LEXER", started);

        var parsed = parser.parse(lexed.tokens());
        AstNode ast = request.wantsTrace() && parsed.query() != null ? ast(parsed.query()) : null;
        if (!parsed.valid()) return invalid(request, tokenTrace, ast, parsed.diagnostics(), "PARSER", started);

        var validated = validator.validate(parsed.query(), request.selectedProposalId());
        if (!validated.valid()) return invalid(request, tokenTrace, ast, validated.diagnostics(), "SEMANTIC", started);

        Preparation preparation = repository.prepare(validated.plan(), authentication);
        if (!preparation.valid()) return invalid(request, tokenTrace, ast, preparation.diagnostics(), "SEMANTIC", started);
        PreparedPlan prepared = preparation.prepared();
        ActionView action = action(prepared);
        long deadline = started + TimeUnit.SECONDS.toNanos(ResearchQueryLanguage.EXECUTION_TIMEOUT_SECONDS);
        CandidateLoad candidates = repository.candidates(authentication);
        ScoreOutcome score = scorer.score(prepared, candidates.studies(), deadline);
        WarehouseReference warehouse = candidates.warehouseSnapshotId() == null ? NO_WAREHOUSE
                : new WarehouseReference("PUBLISHED", candidates.warehouseSnapshotId(), candidates.warehouseAsOf(),
                        "Execution used this immutable historical-research warehouse snapshot.");

        List<QueryDiagnostic> diagnostics = new ArrayList<>(score.diagnostics());
        String status;
        if ("UNAVAILABLE".equals(score.assessmentStatus())) status = "UNAVAILABLE";
        else if (candidates.truncated() || "PARTIAL".equals(score.assessmentStatus())) status = "PARTIAL";
        else status = "EXECUTED";
        if (candidates.truncated()) {
            diagnostics.add(new QueryDiagnostic(EXECUTION, "EXEC_CANDIDATE_LIMIT",
                    "The live catalogue exceeded this runtime profile's " + repository.maxCandidates()
                            + "-study bounded candidate limit; results are PARTIAL.",
                    EXECUTION_SPAN, List.of()));
        }

        List<ScoredStudy> ordered = new ArrayList<>(score.studies());
        ordered.sort(resultComparator(prepared));
        if (ordered.size() > prepared.plan().limit()) ordered = new ArrayList<>(ordered.subList(0, prepared.plan().limit()));
        boolean curator = isCurator(authentication);
        List<ResultView> results = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) results.add(result(index + 1, ordered.get(index), curator));
        return new ExecuteResponse(ResearchQueryLanguage.VERSION, true, status, request.wantsTrace(), tokenTrace, ast,
                new ValidationView(true, "EXECUTION"), action, prepared.plan().algorithmVersion(),
                score.semanticProvider(), score.assessmentStatus(), warehouse, diagnostics, results,
                elapsedMillis(started));
    }

    private static ExecuteResponse invalid(ExecuteRequest request, List<TokenView> tokens, AstNode ast,
                                           List<QueryDiagnostic> diagnostics, String stage, long started) {
        return new ExecuteResponse(ResearchQueryLanguage.VERSION, false, "INVALID", request.wantsTrace(), tokens, ast,
                new ValidationView(false, stage), null, null, null, "UNASSESSED", NO_WAREHOUSE,
                diagnostics, List.of(), elapsedMillis(started));
    }

    private static ExecuteResponse failed(ExecuteRequest request, String status, String code, String message, long started) {
        QueryDiagnostic diagnostic = new QueryDiagnostic(EXECUTION, code, message, EXECUTION_SPAN, List.of());
        return new ExecuteResponse(ResearchQueryLanguage.VERSION, true, status, request.wantsTrace(), List.of(), null,
                new ValidationView(true, "EXECUTION"), null, null, null, "UNAVAILABLE", NO_WAREHOUSE,
                List.of(diagnostic), List.of(), elapsedMillis(started));
    }

    private static ActionView action(PreparedPlan prepared) {
        QueryPlan plan = prepared.plan();
        return new ActionView(plan.target().name(), plan.context() == null ? null : plan.context().kind().name(),
                plan.context() == null || prepared.contextLabel() != null, plan.algorithmVersion(), plan.sortKey().name(),
                plan.direction().name(), plan.limit(), plan.filterCount(),
                "ALLOW_LISTED_AST_INTERPRETER_WITH_BOUND_JDBC");
    }

    private static Comparator<ScoredStudy> resultComparator(PreparedPlan prepared) {
        return (left, right) -> {
            int value = switch (prepared.plan().sortKey()) {
                case RELEVANCE, SIMILARITY -> compareNullable(left.score(), right.score(), prepared.plan().direction());
                case YEAR -> compareNullable(left.study().year(), right.study().year(), prepared.plan().direction());
                case TITLE -> compareNullable(normalize(left.study().title()), normalize(right.study().title()),
                        prepared.plan().direction());
            };
            return value != 0 ? value : left.study().id().toString().compareTo(right.study().id().toString());
        };
    }

    private static <T extends Comparable<? super T>> int compareNullable(T left, T right,
                                                                          ResearchQueryAst.Direction direction) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        int result = left.compareTo(right);
        return direction == ResearchQueryAst.Direction.DESC ? -result : result;
    }

    private static ResultView result(int rank, ScoredStudy scored, boolean curator) {
        var study = scored.study();
        boolean restricted = "RESTRICTED".equalsIgnoreCase(study.visibility())
                || "EMBARGOED".equalsIgnoreCase(study.visibility());
        if (restricted && !curator) {
            return new ResultView(rank, study.id(), null, "Restricted catalogue record", study.academicYear(), study.year(),
                    study.departmentName(), study.lifecycle(), study.visibility(), null, null, List.of(), List.of(),
                    scored.score(), scored.score() == null ? "UNASSESSED" : "ASSESSED",
                    new ScoreComponents(scored.lexicalScore(), scored.tfIdfScore(), scored.semanticScore(), scored.conceptScore()),
                    List.of(), List.of("Protected study evidence was not serialized; academic interpretation remains human-controlled."),
                    true);
        }
        return new ResultView(rank, study.id(), study.code(), study.title(), study.academicYear(), study.year(),
                study.departmentName(), study.lifecycle(), study.visibility(), study.abstractText(), study.methodology(),
                study.keywords(), study.researchAreas(), scored.score(), scored.score() == null ? "UNASSESSED" : "ASSESSED",
                new ScoreComponents(scored.lexicalScore(), scored.tfIdfScore(), scored.semanticScore(), scored.conceptScore()),
                scored.matchedTerms(), scored.explanations(), restricted);
    }

    private void appendAudit(ExecuteRequest request, Authentication authentication, ExecuteResponse response) {
        if (authentication == null || authentication.getName() == null) return;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("querySha256", sha256(request.source() == null ? "" : request.source()));
        snapshot.put("status", response.status());
        if (response.interpretedAction() != null) snapshot.put("action", response.interpretedAction().target());
        if (response.algorithmVersion() != null) snapshot.put("algorithmVersion", response.algorithmVersion());
        snapshot.put("warehouseStatus", response.warehouse().status());
        if (response.warehouse().snapshotId() != null) {
            snapshot.put("warehouseSnapshotId", response.warehouse().snapshotId().toString());
        }
        snapshot.put("assessmentStatus", response.assessmentStatus());
        snapshot.put("resultCount", response.results().size());
        snapshot.put("restrictedResultCount", response.results().stream().filter(ResultView::restricted).count());
        snapshot.put("latencyMillis", response.latencyMillis());
        snapshot.put("diagnosticCodes", response.diagnostics().stream().map(QueryDiagnostic::code).toList());
        audit.append(authentication.getName(), "RESEARCH_QUERY_EXECUTED", "RESEARCH_QUERY", null,
                "Processed a bounded UGNAY research-language statement.", snapshot);
    }

    private static TokenView token(QueryToken token) {
        return new TokenView(token.type().name(), token.lexeme(), token.literal(), token.span());
    }

    private static AstNode ast(Query query) {
        List<AstNode> children = new ArrayList<>();
        children.add(new AstNode("TARGET", query.target().name(), query.span(), List.of()));
        if (query.context() != null) children.add(new AstNode("CONTEXT", query.context().kind().name(),
                query.context().span(), List.of(new AstNode("STRING", query.context().value(), query.context().span(), List.of()))));
        if (query.where() != null) children.add(ast(query.where()));
        if (query.algorithm() != null) children.add(new AstNode("ALGORITHM", query.algorithm().name(), query.span(), List.of()));
        if (query.ordering() != null) children.add(new AstNode("ORDER", query.ordering().key().name()
                + (query.ordering().direction() == null ? "" : " " + query.ordering().direction().name()),
                query.ordering().span(), List.of()));
        if (query.requestedLimit() != null) children.add(new AstNode("LIMIT", query.requestedLimit().toPlainString(),
                query.limitSpan(), List.of()));
        return new AstNode("QUERY", "FIND", query.span(), children);
    }

    private static AstNode ast(Expression expression) {
        if (expression instanceof Group group) return new AstNode("GROUP", null, group.span(), List.of(ast(group.expression())));
        if (expression instanceof Logical logical) return new AstNode("LOGICAL", logical.operator().name(), logical.span(),
                List.of(ast(logical.left()), ast(logical.right())));
        Predicate predicate = (Predicate) expression;
        String value = predicate.value() instanceof StringLiteral string ? string.value()
                : ((NumberLiteral) predicate.value()).value().toPlainString();
        return new AstNode("PREDICATE", predicate.field().name() + " " + predicate.comparator().name(),
                predicate.span(), List.of(new AstNode("VALUE", value, predicate.value().span(), List.of())));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isCurator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_CURATOR".equals(authority.getAuthority()));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
