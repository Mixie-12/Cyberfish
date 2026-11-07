package no.cyberfish.dekoi

import java.net.InetAddress
import java.net.UnknownHostException

/** Whether [ip] lies inside this network. Anything that is not an address of the network's family is outside. */
fun Network.contains(ip: String): Boolean {
    val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return false
    val network = addressBytes(cidr.substringBefore('/')) ?: return false
    val address = addressBytes(ip) ?: return false
    if (network.size != address.size || prefix !in 0..network.size * Byte.SIZE_BITS) return false
    return (0 until prefix).all { bit -> bitAt(network, bit) == bitAt(address, bit) }
}

private fun bitAt(
    bytes: ByteArray,
    bit: Int,
): Int = (bytes[bit / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)) and 1

// IPv4 is parsed here; InetAddress would look up anything that is not a
// valid literal by name. A string with a colon is only ever parsed as IPv6.
private fun addressBytes(text: String): ByteArray? {
    if (':' in text) {
        return try {
            InetAddress.getByName(text).address
        } catch (_: UnknownHostException) {
            null
        }
    }
    val octets = text.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
    return octets.takeIf { it.size == 4 }?.map { it.toByte() }?.toByteArray()
}
