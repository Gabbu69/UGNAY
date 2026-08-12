package com.ugnay.platform.warehouse;

/**
 * Publish this event from an authoritative catalogue/completion transaction.
 * The warehouse listener runs only after that transaction commits.
 */
public record WarehouseRefreshRequested(String actorEmail, Trigger trigger) {
    public WarehouseRefreshRequested {
        if (actorEmail == null || actorEmail.isBlank()) throw new IllegalArgumentException("Warehouse refresh actor is required.");
        if (trigger == null) throw new IllegalArgumentException("Warehouse refresh trigger is required.");
        actorEmail = actorEmail.strip();
    }

    public enum Trigger {
        CATALOGUE_PUBLICATION,
        PROJECT_COMPLETION
    }
}
