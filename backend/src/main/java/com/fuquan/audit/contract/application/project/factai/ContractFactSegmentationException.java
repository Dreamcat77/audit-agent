package com.fuquan.audit.contract.application.project.factai;

/** Deterministic, side-effect-free rejection raised before a fact attempt is claimed. */
public final class ContractFactSegmentationException extends IllegalArgumentException {
    public static final String REASON_CODE = "INPUT_TOO_LARGE";

    private final int canonicalCodePointCount;
    private final int requiredSegmentCount;
    private final int maxSegments;
    private final int maxSegmentCodePoints;

    public ContractFactSegmentationException(int canonicalCodePointCount,
            int requiredSegmentCount, int maxSegments, int maxSegmentCodePoints) {
        super("canonical text exceeds contract fact segment budget");
        if (canonicalCodePointCount < 0 || requiredSegmentCount <= maxSegments
                || maxSegments <= 0 || maxSegmentCodePoints <= 0) {
            throw new IllegalArgumentException("invalid contract fact segmentation diagnostic");
        }
        this.canonicalCodePointCount = canonicalCodePointCount;
        this.requiredSegmentCount = requiredSegmentCount;
        this.maxSegments = maxSegments;
        this.maxSegmentCodePoints = maxSegmentCodePoints;
    }

    public String reasonCode() {
        return REASON_CODE;
    }

    public int canonicalCodePointCount() {
        return canonicalCodePointCount;
    }

    public int requiredSegmentCount() {
        return requiredSegmentCount;
    }

    public int maxSegments() {
        return maxSegments;
    }

    public int maxSegmentCodePoints() {
        return maxSegmentCodePoints;
    }
}
