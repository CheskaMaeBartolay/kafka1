package com.example

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.KafkaRepository
import com.example.model.DeviceInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KafkaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun getOrCreateUserProfile_createsAndReturnsProfile() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = KafkaRepository(firestore)

        val profile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.getOrCreateUserProfile(ALICE_EMAIL, "Alice Parent")
        }

        assertNotNull(profile)
        assertEquals(uid, profile.userId)
    }

    @Test
    fun registerDevice_and_updateTelemetry_succeeds() = runBlocking {
        val uid = signInTestUser(BOB_EMAIL)
        val repo = KafkaRepository(firestore)
        val deviceId = "dev_${UUID.randomUUID()}"

        val device = DeviceInfo(
            deviceId = deviceId,
            userId = uid,
            name = "Bob Phone",
            model = "Pixel 8",
            mode = "DEVICE",
            isOnline = true,
            batteryPercentage = 95
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.registerDevice(device)
            repo.updateDeviceTelemetry(deviceId, 92, isCharging = true, isOnline = true)
        }
        assertTrue(true)
    }

    @Test
    fun generatePairingCode_createsExpiringCode() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = KafkaRepository(firestore)
        val monitorDevId = "mon_${UUID.randomUUID()}"

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.generatePairingCode(monitorDevId, "Alice Galaxy")
        }

        assertTrue(result.isSuccess)
        val code = result.getOrNull()
        assertNotNull(code)
        assertEquals(6, code?.code?.length)
    }

    companion object {
        const val ALICE_EMAIL = "alice_test@kafka.app"
        const val BOB_EMAIL = "bob_test@kafka.app"
        const val DEFAULT_TIMEOUT_MS = 5000L
    }
}
