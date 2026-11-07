package no.cyberfish.dekoi.burner

import java.util.Locale

/** The numbered steps of preparing a card, in the order the window shows them. */
enum class Step(
    private val title: String,
) {
    Card("Choose the card"),
    Connection("Choose the network"),
    Confirm("Confirm"),
    Write("Write the card"),
    Link("Add it to the app"),
    ;

    /** For example "01 / CHOOSE THE CARD". */
    val label: String get() = "%02d / %s".format(Locale.ROOT, ordinal + 1, title.uppercase(Locale.ROOT))
}

/** The step a screen belongs to; null for what comes before the first one. */
val Screen.step: Step?
    get() =
        when (this) {
            Screen.Welcome, Screen.NotWindows, is Screen.NoImager, Screen.Installing -> null
            Screen.Looking, is Screen.ChooseCard, is Screen.ListFailed -> Step.Card
            is Screen.ChooseNetwork -> Step.Connection
            is Screen.Confirm -> Step.Confirm
            Screen.Writing, is Screen.WriteFailed -> Step.Write
            is Screen.Done -> Step.Link
        }
