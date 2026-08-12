package com.ugnay.platform.researchquery;

/** A half-open source range. Offsets are zero-based; lines and columns are one-based. */
public record SourceSpan(
        int startOffset,
        int endOffset,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn) {

    public SourceSpan {
        if (startOffset < 0 || endOffset < startOffset) throw new IllegalArgumentException("Invalid source offsets.");
        if (startLine < 1 || endLine < startLine || startColumn < 1 || endColumn < 1) {
            throw new IllegalArgumentException("Invalid source coordinates.");
        }
    }

    public static SourceSpan covering(SourceSpan first, SourceSpan last) {
        return new SourceSpan(first.startOffset(), last.endOffset(), first.startLine(), first.startColumn(),
                last.endLine(), last.endColumn());
    }
}
