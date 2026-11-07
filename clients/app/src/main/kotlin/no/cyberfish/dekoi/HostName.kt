package no.cyberfish.dekoi

/**
 * What to call a host (docs/design.md, "Network"; ADR 0013): the owner's
 * name for it, else its announced name, else its model, else its maker and
 * kind ("LG TV"), else what it looks like.
 * A private address has no maker, so it gets its own label. Names and
 * models are attacker-controlled: show the result as plain text only.
 * [string] looks up a string resource.
 */
fun hostDisplayName(
    host: NetworkHost,
    string: (Int) -> String,
): String {
    val named = listOf(host.label, host.hostname, host.model).firstOrNull { !it.isNullOrBlank() }
    return when {
        named != null -> named
        host.vendor == null && host.isPrivateMac -> string(R.string.network_private)
        host.vendor == null -> string(kindName(host.kind))
        host.kind == HostKind.UNKNOWN -> host.vendor
        else -> string(R.string.host_maker_kind).format(host.vendor, string(kindName(host.kind)))
    }
}
