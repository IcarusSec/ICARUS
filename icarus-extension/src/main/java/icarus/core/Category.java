package icarus.core;

/**
 * Categories of security tests performed by ICARUS modules.
 */
public enum Category {
    STRUCTURAL,
    TYPE_CONFUSION,
    BOUNDARY,
    INJECTION,
    HEADER_LEAK,
    HEADER_MISSING,
    VERSION_DISCLOSURE,
    JWT_WEAKNESS,
    HTTP_METHOD,
    EXPORT,
    RATE_LIMIT,
    MANUAL
}
