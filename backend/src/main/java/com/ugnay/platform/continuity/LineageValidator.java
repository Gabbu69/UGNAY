package com.ugnay.platform.continuity;

import com.ugnay.platform.shared.PlatformModels.LineageEdge;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public final class LineageValidator {
    public boolean wouldCreateCycle(List<LineageEdge> existing, UUID source, UUID target) {
        if (source.equals(target)) return true;
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (LineageEdge edge : existing) outgoing.computeIfAbsent(edge.sourceId(), ignored -> new java.util.ArrayList<>()).add(edge.targetId());
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            UUID current = queue.remove();
            if (!visited.add(current)) continue;
            if (current.equals(source)) return true;
            queue.addAll(outgoing.getOrDefault(current, List.of()));
        }
        return false;
    }
}
