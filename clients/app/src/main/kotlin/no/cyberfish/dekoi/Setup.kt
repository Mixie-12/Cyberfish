package no.cyberfish.dekoi

import androidx.annotation.StringRes
import no.cyberfish.dekoi.shared.ClaimCode

/** One step of a new DeKoi's setup. */
data class SetupStep(
    @param:StringRes val label: Int,
    val done: Boolean,
)

/** Linked, first start, online, watching; [device] is null until the account's device list has it. */
fun setupSteps(device: Device?): List<SetupStep> =
    listOf(
        SetupStep(R.string.setup_linked, true),
        SetupStep(R.string.setup_first_start, device?.registeredAt != null),
        SetupStep(R.string.setup_online, device?.lastSeenAt != null),
        SetupStep(R.string.setup_protecting, device?.services.orEmpty().isNotEmpty()),
    )

/** True once every setup step is done: the DeKoi is watching the network. */
val Device.isSetUp: Boolean get() = setupSteps(this).all { it.done }

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
