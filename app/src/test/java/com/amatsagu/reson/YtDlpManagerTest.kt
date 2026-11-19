package com.amatsagu.reson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpManagerTest {

    @Test
    fun testYtDlpState_readyCarriesVersion() {
        val state: YtDlpState = YtDlpState.Ready(version = "2025.01.15", statusMessage = "Ready")
        assertTrue(state is YtDlpState.Ready)
        assertEquals("2025.01.15", (state as YtDlpState.Ready).version)
    }

    @Test
    fun testResult_successAndFailure() {
        val success: Result<String> = Result.Success("Update complete")
        assertTrue(success is Result.Success)
        assertEquals("Update complete", (success as Result.Success).data)

        val failure: Result<String> = Result.Failure(RuntimeException("Network error"), "Failed to fetch")
        assertTrue(failure is Result.Failure)
        assertEquals("Failed to fetch", (failure as Result.Failure).message)
    }

    @Test
    fun testErrorRecovery_canRecoverFlag() {
        val errorState = YtDlpState.Error(message = "Corrupted binaries", canRecover = true)
        assertTrue(errorState.canRecover)
    }
}
