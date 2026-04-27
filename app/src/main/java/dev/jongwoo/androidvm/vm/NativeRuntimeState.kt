package dev.jongwoo.androidvm.vm

enum class NativeRuntimeState(val code: Int) {
    UNKNOWN(-1),
    CREATED(1),
    STARTING(2),
    RUNNING(3),
    STOPPED(4),
    ERROR(5),
    ;

    companion object {
        fun fromCode(code: Int): NativeRuntimeState =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
