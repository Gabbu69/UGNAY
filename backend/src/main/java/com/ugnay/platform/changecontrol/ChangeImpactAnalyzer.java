package com.ugnay.platform.changecontrol;

import com.ugnay.platform.shared.PlatformModels.ChangeRequest;
import com.ugnay.platform.shared.PlatformModels.ImpactPreview;
import com.ugnay.platform.shared.PlatformModels.ImpactedArtifact;
import com.ugnay.platform.shared.PlatformModels.Project;
import com.ugnay.platform.shared.PlatformModels.ScopeRisk;
import com.ugnay.platform.shared.PlatformModels.Severity;
import com.ugnay.platform.shared.PlatformModels.TraceItem;
import com.ugnay.platform.shared.PlatformModels.TraceItemType;
import com.ugnay.platform.shared.PlatformModels.TraceLink;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public final class ChangeImpactAnalyzer {

    public ImpactPreview preview(ChangeRequest request, Project project, List<TraceItem> items,
                                 List<TraceLink> links, ScopeRisk risk) {
        Map<UUID, TraceItem> byId = items.stream().collect(Collectors.toMap(TraceItem::id, item -> item));
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (TraceLink link : links) {
            if (!"ACTIVE".equals(link.status())) continue;
            adjacency.computeIfAbsent(link.sourceId(), ignored -> new ArrayList<>()).add(link.targetId());
            adjacency.computeIfAbsent(link.targetId(), ignored -> new ArrayList<>()).add(link.sourceId());
        }

        Map<UUID, List<UUID>> paths = new HashMap<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        for (UUID changed : request.changedItemIds()) {
            if (byId.containsKey(changed)) {
                paths.put(changed, List.of(changed));
                queue.add(changed);
            }
        }
        while (!queue.isEmpty()) {
            UUID current = queue.remove();
            for (UUID next : adjacency.getOrDefault(current, List.of())) {
                if (paths.containsKey(next)) continue;
                List<UUID> path = new ArrayList<>(paths.get(current));
                path.add(next);
                paths.put(next, List.copyOf(path));
                queue.add(next);
            }
        }

        List<ImpactedArtifact> impacted = paths.entrySet().stream()
                .filter(entry -> !request.changedItemIds().contains(entry.getKey()))
                .map(entry -> artifact(byId.get(entry.getKey()), entry.getValue()))
                .sorted((left, right) -> Integer.compare(left.hopCount(), right.hopCount()))
                .toList();
        Set<String> documents = new LinkedHashSet<>();
        for (ImpactedArtifact artifact : impacted) {
            if (artifact.itemType() == TraceItemType.REQUIREMENT) documents.add("Software Requirements Specification");
            if (artifact.itemType() == TraceItemType.FEATURE) documents.add("System Design and Feature Catalogue");
            if (artifact.itemType() == TraceItemType.TEST_CASE) documents.add("Test Plan and Validation Report");
            if (artifact.itemType() == TraceItemType.OUTPUT) documents.add("Final Output and User Documentation");
        }
        boolean current = project.currentBaselineId() != null && project.currentBaselineId().equals(request.basedOnBaselineId());
        return new ImpactPreview(request.id(), request.basedOnBaselineId(), current, risk, impacted, List.copyOf(documents), Instant.now());
    }

    private static ImpactedArtifact artifact(TraceItem item, List<UUID> path) {
        int hops = path.size() - 1;
        boolean stale = item.type() == TraceItemType.TEST_CASE || item.type() == TraceItemType.OUTPUT;
        Severity severity = stale ? Severity.HIGH : hops == 1 ? Severity.HIGH : Severity.MODERATE;
        return new ImpactedArtifact(item.id(), item.key(), item.type(), item.title(), hops, path, severity, stale,
                stale ? "The changed dependency invalidates current verification or output evidence."
                        : "A typed trace path connects this artifact to the proposed change.");
    }
}
