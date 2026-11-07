package no.cyberfish.dekoi

/**
 * What to call a host (docs/design.md, "Network"): its announced name, else
 * its model, else its maker and kind ("LG TV"), else what it looks like.
 * A private address has no maker, so it gets its own label. Names and
 * models are attacker-controlled: show the result as plain text only.
 * [string] looks up a string resource.
 */
fun hostDisplayName(
    host: NetworkHost,
    string: (Int) -> String,
): String {
    val announced = host.hostname?.takeIf { it.isNotBlank() } ?: host.model?.takeIf { it.isNotBlank() }
    return when {
        announced != null -> announced
        host.vendor == null && host.isPrivateMac -> string(R.string.network_private)
        host.vendor == null -> string(kindName(host.kind))
        host.kind == HostKind.UNKNOWN -> host.vendor
        else -> string(R.string.host_maker_kind).format(host.vendor, string(kindName(host.kind)))
    }
}
