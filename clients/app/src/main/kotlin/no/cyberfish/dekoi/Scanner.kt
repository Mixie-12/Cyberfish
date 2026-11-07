package no.cyberfish.dekoi

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scans a QR code with Google Play services' scanner. The scanner runs in its
 * own process with its own UI, so the app needs no camera permission.
 * Returns the raw text, or null if the owner cancelled.
 */
suspend fun scanQrCode(context: Context): String? =
    suspendCancellableCoroutine { continuation ->
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning
            .getClient(context, options)
            .startScan()
            .addOnSuccessListener { continuation.resume(it.rawValue) }
            .addOnCanceledListener { continuation.resume(null) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
