package no.cyberfish.dekoi.burner

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import no.cyberfish.dekoi.shared.ClaimCode
import kotlin.test.Test
import kotlin.test.assertEquals

class QrTest {
    @Test
    fun `the QR code reads back as the claim code payload`() {
        val code = ClaimCode.generate()
        val modules = qrModules(code.qrPayload)

        // Draw it as the screen does: four modules of white around, 8 pixels per module.
        val scale = 8
        val side = (modules.width + 8) * scale
        val pixels =
            IntArray(side * side) { i ->
                val x = i % side / scale - 4
                val y = i / side / scale - 4
                val dark = x in 0 until modules.width && y in 0 until modules.height && modules[x, y]
                if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))

        assertEquals(code.qrPayload, QRCodeReader().decode(bitmap).text)
    }
}
