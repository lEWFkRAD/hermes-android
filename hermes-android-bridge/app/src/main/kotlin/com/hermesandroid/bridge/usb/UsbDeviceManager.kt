package com.hermesandroid.bridge.usb

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class UsbOperationResult(
    val body: Map<String, Any?>,
    val status: Int,
)

/**
 * Authenticated USB-host access for Hermes Bridge.
 *
 * Android remains the permission boundary: a caller cannot open a device until the user accepts
 * the system USB prompt. Connections are process-local, close on detach, and all transfers are
 * bounded so a malformed request cannot allocate unbounded buffers or block indefinitely.
 */
object UsbDeviceManager {
    const val MAX_BULK_BYTES = 1024 * 1024
    const val MAX_CONTROL_BYTES = 64 * 1024
    const val MAX_TIMEOUT_MS = 30_000

    private const val ACTION_USB_PERMISSION = "com.hermesandroid.bridge.USB_PERMISSION"

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingPermissionIds = ConcurrentHashMap.newKeySet<Int>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    private data class Session(
        val id: String,
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val connection: UsbDeviceConnection,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.usbDeviceExtra()
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    device?.let { pendingPermissionIds.remove(it.deviceId) }
                    notifyListeners()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> notifyListeners()

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let { closeForDevice(it.deviceId) }
                    notifyListeners()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        usbManager = appContext.getSystemService(UsbManager::class.java)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // USB attach/detach originates outside this app, so the receiver must accept system
            // broadcasts. The permission broadcast cannot grant access by spoofing: every open
            // operation re-checks UsbManager.hasPermission(device).
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            // The exported/not-exported overload does not exist before API 33. This receiver must
            // accept USB system broadcasts; open() still re-checks UsbManager.hasPermission().
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun attachedDeviceCount(): Int = usbManager.deviceList.size

    fun permittedDeviceCount(): Int = usbManager.deviceList.values.count(usbManager::hasPermission)

    fun connectionCount(): Int = sessions.size

    fun listDevices(): Map<String, Any?> {
        val devices = usbManager.deviceList.values
            .sortedWith(compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId })
            .map(::deviceMap)
        return mapOf(
            "usbHostSupported" to appContext.packageManager.hasSystemFeature("android.hardware.usb.host"),
            "devices" to devices,
            "count" to devices.size,
            "permitted" to devices.count { it["permissionGranted"] == true },
            "openConnections" to sessions.size,
        )
    }

    fun listConnections(): Map<String, Any?> = mapOf(
        "connections" to sessions.values.sortedBy { it.id }.map { session ->
            mapOf(
                "connectionId" to session.id,
                "deviceId" to session.device.deviceId,
                "interfaceId" to session.usbInterface.id,
                "endpointAddresses" to (0 until session.usbInterface.endpointCount).map {
                    session.usbInterface.getEndpoint(it).address
                },
            )
        },
        "count" to sessions.size,
    )

    fun requestPermission(deviceId: Int): UsbOperationResult {
        val device = findDevice(deviceId)
            ?: return error(404, "USB device is no longer attached")
        if (usbManager.hasPermission(device)) {
            return ok(mapOf(
                "status" to "granted",
                "deviceId" to deviceId,
                "permissionGranted" to true,
            ))
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(appContext.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(appContext, deviceId, intent, flags)
        pendingPermissionIds += deviceId
        usbManager.requestPermission(device, permissionIntent)
        return UsbOperationResult(
            mapOf(
                "status" to "permission_requested",
                "deviceId" to deviceId,
                "message" to "Approve the USB access prompt on the Android device",
            ),
            202,
        )
    }

    fun open(deviceId: Int, interfaceId: Int?): UsbOperationResult {
        val device = findDevice(deviceId)
            ?: return error(404, "USB device is no longer attached")
        if (!usbManager.hasPermission(device)) {
            return error(403, "USB permission is required before opening this device")
        }
        val usbInterface = when {
            interfaceId != null -> (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { it.id == interfaceId }
            else -> (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { candidate ->
                    (0 until candidate.endpointCount).any { index ->
                        candidate.getEndpoint(index).type in setOf(
                            UsbConstants.USB_ENDPOINT_XFER_BULK,
                            UsbConstants.USB_ENDPOINT_XFER_INT,
                        )
                    }
                }
                ?: if (device.interfaceCount > 0) device.getInterface(0) else null
        } ?: return error(404, "Requested USB interface was not found")

        val connection = usbManager.openDevice(device)
            ?: return error(500, "Android could not open the USB device")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return error(409, "USB interface is busy or could not be claimed")
        }

        val id = UUID.randomUUID().toString()
        sessions[id] = Session(id, device, usbInterface, connection)
        notifyListeners()
        return ok(mapOf(
            "status" to "connected",
            "connectionId" to id,
            "deviceId" to deviceId,
            "interfaceId" to usbInterface.id,
            "endpoints" to interfaceEndpoints(usbInterface),
        ))
    }

    fun close(connectionId: String): UsbOperationResult {
        val session = sessions.remove(connectionId)
            ?: return error(404, "USB connection was not found")
        closeSession(session)
        notifyListeners()
        return ok(mapOf("status" to "disconnected", "connectionId" to connectionId))
    }

    fun bulkTransfer(
        connectionId: String,
        endpointAddress: Int,
        dataBase64: String?,
        readLength: Int?,
        timeoutMs: Int,
    ): UsbOperationResult {
        val session = sessions[connectionId]
            ?: return error(404, "USB connection was not found")
        val endpoint = findEndpoint(session.usbInterface, endpointAddress)
            ?: return error(404, "USB endpoint is not part of the claimed interface")
        if (endpoint.type !in setOf(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_ENDPOINT_XFER_INT)) {
            return error(400, "Endpoint must use bulk or interrupt transfers")
        }
        validateTimeout(timeoutMs)?.let { return it }

        val isRead = endpoint.direction == UsbConstants.USB_DIR_IN
        val buffer = if (isRead) {
            val length = readLength ?: return error(400, "readLength is required for an IN endpoint")
            validateLength(length, MAX_BULK_BYTES, "readLength")?.let { return it }
            ByteArray(length)
        } else {
            val encoded = dataBase64 ?: return error(400, "dataBase64 is required for an OUT endpoint")
            decodeBase64(encoded, MAX_BULK_BYTES) ?: return error(400, "dataBase64 is invalid or exceeds $MAX_BULK_BYTES bytes")
        }

        val transferred = synchronized(session) {
            session.connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        }
        if (transferred < 0) return error(502, "USB transfer failed or timed out")

        val body = mutableMapOf<String, Any?>(
            "status" to "ok",
            "connectionId" to connectionId,
            "endpointAddress" to endpointAddress,
            "direction" to if (isRead) "in" else "out",
            "bytesTransferred" to transferred,
        )
        if (isRead) {
            body["dataBase64"] = Base64.encodeToString(buffer.copyOf(transferred), Base64.NO_WRAP)
        }
        return ok(body)
    }

    fun controlTransfer(
        connectionId: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        dataBase64: String?,
        readLength: Int?,
        timeoutMs: Int,
    ): UsbOperationResult {
        val session = sessions[connectionId]
            ?: return error(404, "USB connection was not found")
        if (requestType !in 0..255 || request !in 0..255 || value !in 0..65535 || index !in 0..65535) {
            return error(400, "USB control fields are outside their valid ranges")
        }
        validateTimeout(timeoutMs)?.let { return it }

        val isRead = requestType and UsbConstants.USB_DIR_IN != 0
        val buffer = if (isRead) {
            val length = readLength ?: return error(400, "readLength is required for an IN control transfer")
            validateLength(length, MAX_CONTROL_BYTES, "readLength")?.let { return it }
            ByteArray(length)
        } else {
            val encoded = dataBase64 ?: ""
            decodeBase64(encoded, MAX_CONTROL_BYTES)
                ?: return error(400, "dataBase64 is invalid or exceeds $MAX_CONTROL_BYTES bytes")
        }

        val transferred = synchronized(session) {
            session.connection.controlTransfer(
                requestType,
                request,
                value,
                index,
                buffer,
                buffer.size,
                timeoutMs,
            )
        }
        if (transferred < 0) return error(502, "USB control transfer failed or timed out")

        val body = mutableMapOf<String, Any?>(
            "status" to "ok",
            "connectionId" to connectionId,
            "direction" to if (isRead) "in" else "out",
            "bytesTransferred" to transferred,
        )
        if (isRead) {
            body["dataBase64"] = Base64.encodeToString(buffer.copyOf(transferred), Base64.NO_WRAP)
        }
        return ok(body)
    }

    private fun deviceMap(device: UsbDevice): Map<String, Any?> {
        val permitted = usbManager.hasPermission(device)
        return mapOf(
            "deviceId" to device.deviceId,
            "deviceName" to device.deviceName,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "deviceClass" to device.deviceClass,
            "deviceSubclass" to device.deviceSubclass,
            "deviceProtocol" to device.deviceProtocol,
            "manufacturer" to if (permitted) safeDescriptor { device.manufacturerName } else null,
            "product" to if (permitted) safeDescriptor { device.productName } else null,
            "version" to if (permitted) safeDescriptor { device.version } else null,
            "serialNumber" to if (permitted) safeDescriptor { device.serialNumber } else null,
            "permissionGranted" to permitted,
            "permissionPending" to pendingPermissionIds.contains(device.deviceId),
            "interfaceCount" to device.interfaceCount,
            "interfaces" to (0 until device.interfaceCount).map { interfaceMap(device.getInterface(it)) },
        )
    }

    private fun interfaceMap(usbInterface: UsbInterface): Map<String, Any?> = mapOf(
        "interfaceId" to usbInterface.id,
        "alternateSetting" to usbInterface.alternateSetting,
        "name" to usbInterface.name,
        "class" to usbInterface.interfaceClass,
        "subclass" to usbInterface.interfaceSubclass,
        "protocol" to usbInterface.interfaceProtocol,
        "endpointCount" to usbInterface.endpointCount,
        "endpoints" to interfaceEndpoints(usbInterface),
    )

    private fun interfaceEndpoints(usbInterface: UsbInterface): List<Map<String, Any?>> =
        (0 until usbInterface.endpointCount).map { endpointMap(usbInterface.getEndpoint(it)) }

    private fun endpointMap(endpoint: UsbEndpoint): Map<String, Any?> = mapOf(
        "address" to endpoint.address,
        "number" to endpoint.endpointNumber,
        "direction" to if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out",
        "type" to when (endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            else -> "unknown"
        },
        "maxPacketSize" to endpoint.maxPacketSize,
        "interval" to endpoint.interval,
    )

    private fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    private fun findEndpoint(usbInterface: UsbInterface, address: Int): UsbEndpoint? =
        (0 until usbInterface.endpointCount)
            .map(usbInterface::getEndpoint)
            .firstOrNull { it.address == address }

    private fun closeForDevice(deviceId: Int) {
        sessions.entries
            .filter { it.value.device.deviceId == deviceId }
            .forEach { (id, session) ->
                if (sessions.remove(id, session)) closeSession(session)
            }
    }

    private fun closeSession(session: Session) {
        runCatching { session.connection.releaseInterface(session.usbInterface) }
        runCatching { session.connection.close() }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener) }
    }

    private fun validateTimeout(timeoutMs: Int): UsbOperationResult? =
        if (timeoutMs !in 1..MAX_TIMEOUT_MS) {
            error(400, "timeoutMs must be between 1 and $MAX_TIMEOUT_MS")
        } else null

    private fun validateLength(length: Int, maximum: Int, field: String): UsbOperationResult? =
        if (length !in 0..maximum) error(400, "$field must be between 0 and $maximum") else null

    private fun decodeBase64(encoded: String, maximum: Int): ByteArray? {
        return try {
            // Reject obviously oversized input before decoding to avoid a transient large allocation.
            if (encoded.length > ((maximum + 2) / 3) * 4 + 4) return null
            Base64.decode(encoded, Base64.DEFAULT).takeIf { it.size <= maximum }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private inline fun <T> safeDescriptor(block: () -> T?): T? = try {
        block()
    } catch (_: SecurityException) {
        null
    }

    private fun ok(body: Map<String, Any?>) = UsbOperationResult(body, 200)

    private fun error(status: Int, message: String) = UsbOperationResult(mapOf("error" to message), status)

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
