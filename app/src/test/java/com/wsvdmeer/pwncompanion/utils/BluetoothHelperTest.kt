package com.wsvdmeer.pwncompanion.utils

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothHelperTest {

    @Test
    fun `unbonded BLE peripheral is rejected`() {
        assertFalse(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_NONE,
                deviceType = BluetoothDevice.DEVICE_TYPE_LE,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = null,
            )
        )
    }

    @Test
    fun `bonded BLE-only peripheral is rejected`() {
        assertFalse(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_LE,
                majorDeviceClass = BluetoothClass.Device.Major.HEALTH,
                deviceName = "Body Scan C4",
            )
        )
    }

    @Test
    fun `bonded Bluetooth headphones are rejected`() {
        assertFalse(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                deviceName = "OpenFit 2+ by Shokz",
                uuidStrings = listOf(
                    "0000110b-0000-1000-8000-00805f9b34fb",
                    "0000111e-0000-1000-8000-00805f9b34fb",
                ),
            )
        )
    }

    @Test
    fun `bonded uncategorized non-PAN accessory is rejected`() {
        assertFalse(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = "Body+ E6",
                uuidStrings = listOf("00001101-0000-1000-8000-00805f9b34fb"),
            )
        )
    }

    @Test
    fun `bonded Pwnagotchi with default name is accepted`() {
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = "pwnagotchi",
                uuidStrings = listOf("0000110e-0000-1000-8000-00805f9b34fb"),
            )
        )
    }

    @Test
    fun `bonded custom-named device advertising PANU or NAP UUID is accepted`() {
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = "custom-unit",
                uuidStrings = listOf("00001115-0000-1000-8000-00805f9b34fb"),
            )
        )
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = "custom-unit",
                uuidStrings = listOf("00001116-0000-1000-8000-00805f9b34fb"),
            )
        )
    }

    @Test
    fun `bonded computer or networking class device is accepted`() {
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.COMPUTER,
                deviceName = "kali-pi",
            )
        )
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.NETWORKING,
                deviceName = "ap-bridge",
            )
        )
    }

    @Test
    fun `bonded Raspberry Pi device name is accepted`() {
        assertTrue(
            BluetoothHelper.isCandidatePanDevice(
                bondState = BluetoothDevice.BOND_BONDED,
                deviceType = BluetoothDevice.DEVICE_TYPE_DUAL,
                majorDeviceClass = BluetoothClass.Device.Major.UNCATEGORIZED,
                deviceName = "raspberrypi",
            )
        )
    }
}
