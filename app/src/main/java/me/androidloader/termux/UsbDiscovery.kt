package me.androidloader.termux

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Finds the attached iPhone through Android's USB host API.
 *
 * The point of doing this in the app rather than parsing `termux-usb -l` is that
 * [UsbDevice.getDeviceName] already returns the usbfs path, which is exactly the
 * argument `termux-usb` requires. `termux-usb` refuses to do anything without it,
 * so a path has to come from somewhere, and the USB host API is already available.
 *
 * This also means the app learns about the device independently of whether
 * usbmuxd happens to be running.
 */
object UsbDiscovery {

    /** Apple's USB vendor ID. */
    const val APPLE_VENDOR_ID = 0x05AC

    /** A device seen on the bus. */
    data class Found(
        /** The usbfs path, for example `/dev/bus/usb/001/002`. */
        val path: String,
        val vendorId: Int,
        val productId: Int,
        val deviceName: String,
        val hasPermission: Boolean,
    ) {
        val isApple: Boolean get() = vendorId == APPLE_VENDOR_ID

        /** A short human-readable description for the UI. */
        val description: String
            get() = buildString {
                append(if (isApple) "Apple" else "USB")
                append(" device ")
                append(String.format("%04x", vendorId))
                append(':')
                append(String.format("%04x", productId))
                if (!hasPermission) append(" (permission not granted)")
            }
    }

    /** Every attached USB device, newest first. */
    fun devices(context: Context): List<Found> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return manager.deviceList.values
            .map { device ->
                Found(
                    path = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    deviceName = device.deviceName,
                    hasPermission = manager.hasPermission(device),
                )
            }
            .sortedWith(compareByDescending<Found> { it.isApple }.thenBy { it.path })
    }

    /** The Apple device, preferring one we already hold permission for. */
    fun appleDevice(context: Context): Found? =
        devices(context)
            .filter { it.isApple }
            .firstOrNull { it.hasPermission }
            ?: devices(context).firstOrNull { it.isApple }

    /**
     * Asks for permission to use [device].
     *
     * Not strictly required, because `termux-usb -r` raises its own dialog and
     * does the claiming. Doing it here means the app can report permission
     * problems itself instead of the daemon failing silently later.
     */
    fun requestPermission(context: Context, device: Found): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false
        val target = manager.deviceList[device.deviceName] ?: return false
        if (manager.hasPermission(target)) return true
        return try {
            // The permission dialog is asynchronous; the result arrives at the
            // receiver registered in MainActivity, so true here only means the
            // request was made.
            manager.requestPermission(target, permissionIntent(context))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun permissionIntent(context: Context) =
        android.app.PendingIntent.getBroadcast(
            context,
            0,
            android.content.Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            // The framework has to add EXTRA_PERMISSION_GRANTED, so the
            // PendingIntent must be mutable.
            android.app.PendingIntent.FLAG_MUTABLE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    0
                },
        )

    /** Broadcast action carrying the user's answer to a permission request. */
    const val ACTION_USB_PERMISSION = "me.androidloader.USB_PERMISSION"
}
