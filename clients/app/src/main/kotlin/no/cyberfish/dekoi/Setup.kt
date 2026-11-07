package no.cyberfish.dekoi

import androidx.annotation.StringRes
import no.cyberfish.dekoi.shared.ClaimCode

/** The setup checklist, in the order the steps happen. */
enum class SetupStep(
    @param:StringRes val label: Int,
) {
    LINKED(R.string.setup_linked),
    FIRST_START(R.string.setup_first_start),
    ONLINE(R.string.setup_online),
    WATCHING(R.string.setup_protecting),
}

/**
 * Whether [step] is done; null is a DeKoi just linked that the account's
 * device list does not have yet. Linking always is done, since the owner
 * sees only DeKois linked to their account.
 */
fun Device?.hasDone(step: SetupStep): Boolean =
    when (step) {
        SetupStep.LINKED -> true
        SetupStep.FIRST_START -> this?.registeredAt != null
        SetupStep.ONLINE -> this?.lastSeenAt != null
        SetupStep.WATCHING -> !this?.services.isNullOrEmpty()
    }

/** True once every setup step is done: the DeKoi is watching the network. */
val Device.isSetUp: Boolean get() = SetupStep.entries.all { hasDone(it) }

/** A claim code is shown in groups of this many characters. */
const val CODE_GROUP = 4

/** What the code field keeps of what was typed or pasted: a pasted QR payload as its code, otherwise up to 26 letters and digits. */
fun typedCode(raw: String): String = ClaimCode.parse(raw)?.value ?: raw.filter(Char::isLetterOrDigit).uppercase().take(ClaimCode.LENGTH)

/** Where [offset] in a typed code falls once a space separates each group. */
fun groupedOffset(offset: Int): Int = if (offset == 0) 0 else offset + (offset - 1) / CODE_GROUP

/** The reverse of [groupedOffset] for a code of [length] characters. */
fun ungroupedOffset(
    offset: Int,
    length: Int,
): Int = (offset - offset / (CODE_GROUP + 1)).coerceIn(0, length)
