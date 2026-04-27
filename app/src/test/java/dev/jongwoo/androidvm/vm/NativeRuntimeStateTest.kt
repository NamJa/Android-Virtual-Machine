package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRuntimeStateTest {
    @Test
    fun fromCodeMapsKnownStates() {
        assertEquals(NativeRuntimeState.CREATED, NativeRuntimeState.fromCode(1))
        assertEquals(NativeRuntimeState.RUNNING, NativeRuntimeState.fromCode(3))
        assertEquals(NativeRuntimeState.ERROR, NativeRuntimeState.fromCode(5))
    }

    @Test
    fun fromCodeFallsBackToUnknown() {
        assertEquals(NativeRuntimeState.UNKNOWN, NativeRuntimeState.fromCode(0))
        assertEquals(NativeRuntimeState.UNKNOWN, NativeRuntimeState.fromCode(42))
    }
}
