package dev.yanianz.bravenspawner.convert;

/**
 * Verdict of the conversion guard chain for one vanilla spawner.
 *
 * <p>Only {@link #CONVERTIBLE} performs a conversion. Every other value is a
 * skip reason and is counted, never thrown: a scan receipt groups skips by
 * these labels, so each one carries human-readable text.
 */
public enum ConvertCheck {

    CONVERTIBLE("convertible"),
    NOT_READY("storage not ready"),
    DISABLED("conversion disabled"),
    BLACKLISTED("blacklisted world"),
    ALREADY_MANAGED("already managed"),
    NO_PRODUCT("no product for mob"),
    CHUNK_CAP("chunk cap reached"),
    THROTTLED("throttled");

    private final String label;

    ConvertCheck(String label) {
        this.label = label;
    }

    /** Short human-readable label used in scan receipts. */
    public String label() {
        return label;
    }

    /** True only for {@link #CONVERTIBLE}. */
    public boolean convertible() {
        return this == CONVERTIBLE;
    }
}
