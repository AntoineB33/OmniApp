package org.example.project

import org.example.project.scheduler.domain.PeriodCombination
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKinds

/**
 * The default combination rules with each named kind's "always present with it" rule (`when <kind> then …`,
 * [PeriodKinds.companionRule]) set to the given kinds — dropped where the set is empty. What a test wrote as a
 * companion set before 2026-10-01, when companions became rules.
 */
fun combinationsWithCompanions(vararg entries: Pair<String, Set<String>>): List<PeriodCombination> {
    val replaced = entries.map { "companion-${it.first}" }.toSet()
    return PeriodKinds.DEFAULT_COMBINATIONS.filterNot { it.id in replaced } +
        entries.filter { it.second.isNotEmpty() }.map { (kind, companions) -> PeriodKinds.companionRule(kind, companions) }
}

/** A [PeriodKindConfig] at the default drawings, with [combinationsWithCompanions]. */
fun configWithCompanions(vararg entries: Pair<String, Set<String>>): PeriodKindConfig =
    PeriodKindConfig(combinations = combinationsWithCompanions(*entries))
