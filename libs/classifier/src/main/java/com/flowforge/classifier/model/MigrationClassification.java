package com.flowforge.classifier.model;

import java.util.List;

/**
 * Classification results for migration difficulty and coupling.
 */
public sealed interface MigrationClassification {

    record ReactiveComplexityClass(
        String classFqn,
        MigrationDifficulty difficulty,
        double confidence,
        List<String> reasons
    ) implements MigrationClassification {}

    record FrameworkCouplingClass(
        String classFqn,
        CouplingLevel coupling,
        double confidence,
        List<String> frameworkApis
    ) implements MigrationClassification {}

    record DataAccessPatternClass(
        String classFqn,
        DataAccessPattern pattern,
        double confidence,
        String migrationNote
    ) implements MigrationClassification {}

    enum MigrationDifficulty { TRIVIAL, LOW, MEDIUM, HIGH, VERY_HIGH }
    enum CouplingLevel { NONE, LIGHT, MODERATE, TIGHT, LOCKED_IN }
    enum DataAccessPattern { JPA, RAW_SQL, REACTIVE_MONGO, REACTIVE_R2DBC, MIXED, NONE }
}
