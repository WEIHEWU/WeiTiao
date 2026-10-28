package com.weitiao.tuning.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NativePropCowTest {

    private class ByteArrayMemoryAccessor(
        val buffer: ByteArray,
        val baseAddr: Long = 0x1000_0000L,
    ) : NativePropCow.MemoryAccessor {

        private fun indexOf(addr: Long): Int = (addr - baseAddr).toInt()

        override fun getByte(addr: Long): Byte = buffer[indexOf(addr)]

        override fun putByte(addr: Long, value: Byte) {
            buffer[indexOf(addr)] = value
        }

        override fun getIntLE(addr: Long): Int {
            val idx = indexOf(addr)
            return (buffer[idx].toInt() and 0xFF) or
                ((buffer[idx + 1].toInt() and 0xFF) shl 8) or
                ((buffer[idx + 2].toInt() and 0xFF) shl 16) or
                ((buffer[idx + 3].toInt() and 0xFF) shl 24)
        }

        override fun putIntLE(addr: Long, value: Int) {
            val idx = indexOf(addr)
            buffer[idx] = (value and 0xFF).toByte()
            buffer[idx + 1] = ((value ushr 8) and 0xFF).toByte()
            buffer[idx + 2] = ((value ushr 16) and 0xFF).toByte()
            buffer[idx + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }

    private fun createBionicPropArea(sizeBytes: Int = 128 * 1024): Pair<ByteArrayMemoryAccessor, NativePropCow.PropMapArea> {
        val mem = ByteArrayMemoryAccessor(ByteArray(sizeBytes))
        val area = NativePropCow.PropMapArea(
            start = mem.baseAddr,
            end = mem.baseAddr + sizeBytes,
            length = sizeBytes.toLong(),
            path = "/dev/__properties__/u:object_r:build_prop:s0",
            isWritable = true,
        )
        // Initialize Bionic prop_area header:
        // root prop_bt occupies first 24 bytes of data section (bytes_used = 24)
        mem.putIntLE(area.start + 0, 24) // bytes_used
        mem.putIntLE(area.start + 4, 1) // serial
        mem.putIntLE(area.start + 8, NativePropCow.PROP_AREA_MAGIC)
        mem.putIntLE(area.start + 12, NativePropCow.PROP_AREA_VERSION)
        return mem to area
    }

    private fun readPropValue(mem: NativePropCow.MemoryAccessor, propInfoAddr: Long): String {
        val serial = mem.getIntLE(propInfoAddr)
        val len = (serial ushr 24) and 0xFF
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = mem.getByte(propInfoAddr + NativePropCow.PROP_INFO_VALUE_OFFSET + i)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readPropKey(mem: NativePropCow.MemoryAccessor, propInfoAddr: Long): String {
        val out = java.io.ByteArrayOutputStream()
        var offset = propInfoAddr + NativePropCow.PROP_INFO_NAME_OFFSET
        while (true) {
            val b = mem.getByte(offset++)
            if (b == 0.toByte()) break
            out.write(b.toInt())
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    @Test
    fun radixTrieInsertAndLookupCrossBrandProps() {
        val (mem, area) = createBionicPropArea()

        // 1. Simulate initial Samsung ROM properties
        val initialSamsung = NativePropCow.prepareProps(
            mapOf(
                "ro.product.model" to "SM-S9280",
                "ro.product.brand" to "samsung",
                "ro.product.device" to "e3q",
            ),
        )
        for (prop in initialSamsung) {
            val res = NativePropCow.insertOrUpdatePropInTrie(mem, area, prop)
            assertNotNull(res)
            assertTrue(res!!.insertedNewNode)
        }

        // Verify Bionic trie lookup finds existing Samsung props
        val modelAddr = NativePropCow.findPropInTrie(mem, area, "ro.product.model".toByteArray())
        assertTrue(modelAddr != 0L)
        assertEquals("SM-S9280", readPropValue(mem, modelAddr))
        assertEquals("ro.product.model", readPropKey(mem, modelAddr))

        // 2. Apply iPhone Duo full spoof props (mix of existing Samsung keys + missing cross-brand keys)
        val iphoneDuo = requireNotNull(DeviceModels.findByName("iPhone Duo"))
        val duoPropsMap = CscSpoof.buildDevicePropsForProfile(iphoneDuo)
        val preparedDuo = NativePropCow.prepareProps(duoPropsMap)

        // First pass: find existing in trie & overwrite in-place
        val matched = NativePropCow.findMatchedPropsInArea(mem, area, preparedDuo)
        assertEquals(3, matched.size)
        for ((addr, prop) in matched) {
            NativePropCow.writePropValue(mem, addr, prop.valueBytes)
        }

        // Second pass: insert all missing cross-brand keys into Bionic radix trie
        var insertedCount = 0
        for (prop in preparedDuo) {
            val res = NativePropCow.insertOrUpdatePropInTrie(mem, area, prop)
            assertNotNull("Failed to insert/update ${prop.name}", res)
            if (res!!.insertedNewNode) insertedCount++
        }
        assertEquals(preparedDuo.size - 3, insertedCount)

        // Verify every single property in CscSpoof (including Xiaomi/OPPO/vivo/Huawei market names & bootimage)
        // is now discoverable via Bionic Radix Trie traversal with exact values!
        for ((expectedKey, expectedVal) in duoPropsMap) {
            val addr = NativePropCow.findPropInTrie(mem, area, expectedKey.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Expected $expectedKey to be found in Bionic Radix Trie", addr != 0L)
            assertEquals("Mismatch for $expectedKey", expectedVal, readPropValue(mem, addr))
            assertEquals("Key name mismatch at prop_info", expectedKey, readPropKey(mem, addr))
            val serial = mem.getIntLE(addr)
            assertEquals("kLongFlag bit 16 must be 0 for $expectedKey", 0, serial and (1 shl 16))
            assertEquals("Dirty bit 0 must be 0 after write for $expectedKey", 0, serial and 1)
        }
    }

    @Test
    fun hotSwitchUpdatesInPlaceWithoutAllocatingExtraMemory() {
        val (mem, area) = createBionicPropArea()

        val duoProps = NativePropCow.prepareProps(
            CscSpoof.buildDevicePropsForProfile(requireNotNull(DeviceModels.findByName("iPhone Duo"))),
        )
        for (prop in duoProps) {
            assertNotNull(NativePropCow.insertOrUpdatePropInTrie(mem, area, prop))
        }
        val bytesUsedAfterFirstSpoof = mem.getIntLE(area.start)
        assertTrue(bytesUsedAfterFirstSpoof > 24)

        // Hot-switch to Xiaomi 17 Ultra in the same process
        val xiaomiProps = NativePropCow.prepareProps(
            CscSpoof.buildDevicePropsForProfile(requireNotNull(DeviceModels.findByName("Xiaomi 17 Ultra"))),
        )
        for (prop in xiaomiProps) {
            val res = NativePropCow.insertOrUpdatePropInTrie(mem, area, prop)
            assertNotNull(res)
            assertFalse("Hot-switch must update existing node in-place for ${prop.name}", res!!.insertedNewNode)
        }

        // Verify zero extra bytes were allocated during hot-switch
        val bytesUsedAfterHotSwitch = mem.getIntLE(area.start)
        assertEquals(bytesUsedAfterFirstSpoof, bytesUsedAfterHotSwitch)

        val marketAddr = NativePropCow.findPropInTrie(mem, area, "ro.product.marketname".toByteArray())
        assertEquals("Xiaomi 17 Ultra", readPropValue(mem, marketAddr))

        val socAddr = NativePropCow.findPropInTrie(mem, area, "ro.soc.model".toByteArray())
        assertEquals("SM8850", readPropValue(mem, socAddr))
    }

    @Test
    fun outOfSpaceAreaFailsGracefullyWithoutCorruption() {
        // Tiny area that can only fit 1 property
        val (mem, area) = createBionicPropArea(sizeBytes = 320)
        val p1 = NativePropCow.prepareProps(mapOf("ro.a" to "1")).first()
        val p2 = NativePropCow.prepareProps(mapOf("ro.very.long.property.name.that.exceeds.capacity" to "value")).first()

        assertNotNull(NativePropCow.insertOrUpdatePropInTrie(mem, area, p1))
        val bytesBeforeOverflow = mem.getIntLE(area.start)

        // Second insert exceeds 320-byte capacity and must return null safely
        assertNull(NativePropCow.insertOrUpdatePropInTrie(mem, area, p2))

        // First property must still be intact and readable
        val p1Addr = NativePropCow.findPropInTrie(mem, area, "ro.a".toByteArray())
        assertTrue(p1Addr != 0L)
        assertEquals("1", readPropValue(mem, p1Addr))
        assertTrue(mem.getIntLE(area.start) >= bytesBeforeOverflow)
    }

    @Test
    fun multiContextRoutingSelectsVendorOdmBuildAndExportedDefaultProp() {
        val areas = listOf(
            NativePropCow.PropMapArea(0x1000, 0x2000, 0x1000, "/dev/__properties__/u:object_r:build_prop:s0"),
            NativePropCow.PropMapArea(0x2000, 0x3000, 0x1000, "/dev/__properties__/u:object_r:default_prop:s0"),
            NativePropCow.PropMapArea(0x3000, 0x4000, 0x1000, "/dev/__properties__/u:object_r:exported_default_prop:s0"),
            NativePropCow.PropMapArea(0x4000, 0x5000, 0x1000, "/dev/__properties__/u:object_r:vendor_default_prop:s0"),
            NativePropCow.PropMapArea(0x5000, 0x6000, 0x1000, "/dev/__properties__/u:object_r:build_vendor_prop:s0"),
            NativePropCow.PropMapArea(0x6000, 0x7000, 0x1000, "/dev/__properties__/u:object_r:build_odm_prop:s0"),
        )

        val vendorTargets = NativePropCow.selectTargetAreasForInsertion("ro.vendor.oplus.market.name", areas, null)
        assertTrue(vendorTargets.any { it.path.endsWith("build_vendor_prop:s0") })
        assertTrue(vendorTargets.any { it.path.endsWith("vendor_default_prop:s0") })
        assertTrue(vendorTargets.any { it.path.endsWith("exported_default_prop:s0") })
        assertTrue(vendorTargets.any { it.path.endsWith("build_prop:s0") })
        assertTrue(vendorTargets.any { it.path.endsWith("default_prop:s0") })

        val odmTargets = NativePropCow.selectTargetAreasForInsertion("ro.product.odm.marketname", areas, null)
        assertTrue(odmTargets.any { it.path.endsWith("build_odm_prop:s0") })
        assertTrue(odmTargets.any { it.path.endsWith("exported_default_prop:s0") })
        assertTrue(odmTargets.any { it.path.endsWith("default_prop:s0") })

        val vivoTargets = NativePropCow.selectTargetAreasForInsertion("ro.vivo.market.name", areas, null)
        assertTrue(vivoTargets.any { it.path.endsWith("build_prop:s0") })
        assertTrue(vivoTargets.any { it.path.endsWith("exported_default_prop:s0") })
        assertTrue(vivoTargets.any { it.path.endsWith("default_prop:s0") })
    }
}
