package io.github.lcebot.clipsync.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing vocabulary.
 *
 * Named for the RULE, not for the value: two gaps that are both 8 dp for different reasons get
 * different names, so changing one never silently moves the other. Each entry says why it is what
 * it is, because a name cannot carry that.
 */
object Dimens {
    /** Between sub-groups of a section. The one to copy when a sub-group is added. */
    val SpacingGroup = 8.dp

    /**
     * Between two controls inside one card. The same number as a group gap, for a different
     * reason: a card's contents are spaced like each other, a page's groups like each other, and
     * either can move without dragging the other.
     */
    val SpacingField = 8.dp

    /**
     * A seam: a switch and the card it governs, or one settings button and the next. Tighter than a
     * group gap, so the two read as one unit while staying two surfaces.
     */
    val SpacingSeam = 4.dp

    /**
     * Under a section title, and above a switch card, which introduces a group the way a title
     * does. Anything larger makes the groups inside a section look further apart than the sections
     * are from each other.
     */
    val SpacingHeading = 6.dp

    /** Above a section title: what separates one whole section from the last. */
    val SpacingSection = 12.dp

    /** Between a label and the control it names, such as a slider and its readout line. */
    val SpacingLabel = 12.dp

    /**
     * The inset of every settings card's body, and the horizontal margin of a switch alone in its
     * card, which puts the switch's label on the fields' left edge and its track's end on their
     * right edge.
     */
    val CardBodyInset = 12.dp

    /**
     * A status card in the peer sheet: a denser list of read-only values rather than a column of
     * controls, so it takes the standard card padding.
     */
    val CardPadding = 16.dp

    /** The page's own side margin: the column every card's edge sits on. */
    val PageGutter = 16.dp

    /** The sheet's, wider: its title and group headings sit on it and the cards line up with them. */
    val SheetGutter = 24.dp

    /**
     * The "no path enabled" error line, indented to the text fields' label column so it reads as
     * one of their errors rather than a note about the page.
     */
    val ErrorGutter = 28.dp

    /**
     * The log's text inset. Smaller than the page gutter: a monospace log is wanted wide, and it
     * has no card edges to line up with.
     */
    val LogGutter = 12.dp

    /** The gap between the two actions of the floating pair. */
    val ActionGap = 12.dp

    /** The welcome screen's one horizontal measurement, so every child lines up on one edge. */
    val WelcomeGutter = 24.dp
}
