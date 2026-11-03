package dev.brikk.ducklake.corpus

/**
 * Which DuckLake ATTACH targets a `metadataRewriter` (the backend axis) moves to the external backend.
 */
enum class MetadataRewriteScope {
    /** Every attach with a data path (0.13 behavior). */
    ALL_ATTACHES,

    /**
     * Only lakes derived from the file's `DUCKLAKE_CONNECTION` test-env, matching upstream backend test configs.
     * Hardcoded fixture lakes stay on the backend the file names, so native metadata SQL against them keeps its
     * meaning; their mirrors are measured skips (`backend-local-fixture`), not failures.
     */
    CONNECTION_ENV_ONLY,
}
