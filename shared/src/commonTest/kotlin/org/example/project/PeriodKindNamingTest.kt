package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.periodKindNamed

/**
 * PRD §8: **a kind of restrictive period is offered under ONE name wherever it is offered.**
 *
 * The anomaly this pins: the "add…" window's kind picker listed the kinds under their STORED names, so the
 * user looking for *inactivity* — the word the "edit…" chooser uses, and the word the editor that picker
 * opens is headed with — found "no task allowed" and read it as missing.
 *
 * It is fixed by the RENAME rather than by a label table: the stored kinds are the user's words now
 * ([PeriodKinds.INACTIVITY] is `inactivity`, [PeriodKinds.NO_SCREEN] is `no screen`), so no surface can pick
 * its own spelling because there is no second spelling to pick. The one name still computed apart is the
 * title a period CARRIES on the grid ("Inactivity"), capitalized because it heads a box.
 *
 * The way back matters as much: **any of a kind's names must RESOLVE to it** — the two spellings a payload
 * written before the rename still holds included — or typing the word an old account stores would offer to
 * create a second kind differing from the first only in spelling.
 */
class PeriodKindNamingTest {

    private val kinds = SchedulerState.empty().allPeriodKinds

    /** Every kind the picker can offer, in the words the rest of the calendar uses. */
    @Test
    fun everyOfferedKindIsNamedInTheUsersWords() {
        assertEquals(
            listOf("inactivity", "sleep", "no screen", "before bed", "no computer unlocked", "no phone unlocked"),
            kinds,
        )
        // The two the user reported are reachable under the words they looked for, and neither legacy
        // spelling is offered any more: the stored name IS the menu row.
        assertTrue(kinds.contains("inactivity"))
        assertTrue(kinds.none { it == "no task allowed" || it == "no on-screen task" })
    }

    /** A kind the account defined was named by the user already, so it needs no translation either. */
    @Test
    fun anAccountDefinedKindIsItsOwnName() {
        val withOwn = SchedulerState.empty().copy(periodKinds = listOf("deep work")).allPeriodKinds
        assertEquals(kinds + "deep work", withOwn)
    }

    /** Any of a kind's names resolves to the one kind, whichever spelling the user knows. */
    @Test
    fun everySpellingOfAKindResolvesToIt() {
        // Its own word, its grid title, a payload's legacy spelling, and any casing or padding of those.
        listOf("inactivity", "Inactivity", "  INACTIVITY  ", "no task allowed")
            .forEach { typed -> assertEquals(PeriodKinds.INACTIVITY, periodKindNamed(typed, kinds), typed) }
        listOf("no screen", "No screen", "no on-screen task")
            .forEach { typed -> assertEquals(PeriodKinds.NO_SCREEN, periodKindNamed(typed, kinds), typed) }
        assertEquals(PeriodKinds.SLEEP, periodKindNamed("Sleep", kinds))
        assertEquals(PeriodKinds.BEFORE_BED, periodKindNamed("Before bed", kinds))
        assertEquals(PeriodKinds.NO_PHONE_UNLOCKED, periodKindNamed("No phone unlocked", kinds))
        assertEquals(PeriodKinds.NO_COMPUTER_UNLOCKED, periodKindNamed("no computer unlocked", kinds))
    }

    /**
     * Which is what stops the picker minting a second grey kind: `no task allowed` is [PeriodKinds.INACTIVITY]'s
     * own former name, so an old account typing it PICKS the built-in instead of creating one beside it. A
     * genuinely new name still resolves to nothing, which is what lets the field offer to create.
     */
    @Test
    fun aNameThatIsNobodysIsTheOnlyOneLeftToCreate() {
        assertEquals("", periodKindNamed("deep work", kinds))
        assertEquals("", periodKindNamed("", kinds))
        assertEquals("", periodKindNamed("   ", kinds))
        assertEquals("deep work", periodKindNamed("deep work", kinds + "deep work"))
    }
}
