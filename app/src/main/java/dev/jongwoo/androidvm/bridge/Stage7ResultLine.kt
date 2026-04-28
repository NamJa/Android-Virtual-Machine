package dev.jongwoo.androidvm.bridge

/**
 * Stable formatting for the Stage 07 final gate diagnostic line. Tests pin the field set here so
 * the docs and the runtime stay in sync.
 */
data class Stage7ResultLine(
    val manifest: Boolean,
    val policy: Boolean,
    val broker: Boolean,
    val audit: Boolean,
    val dispatcher: Boolean,
    val ui: Boolean,
    val deviceProfile: Boolean,
    val output: Boolean,
    val clipboard: Boolean,
    val location: Boolean,
    val unsupportedMedia: Boolean,
    val regressions: Boolean,
) {
    val passed: Boolean
        get() = manifest && policy && broker && audit && dispatcher && ui && deviceProfile &&
            output && clipboard && location && unsupportedMedia && regressions

    fun format(): String {
        return "STAGE7_RESULT passed=$passed manifest=$manifest policy=$policy " +
            "broker=$broker audit=$audit dispatcher=$dispatcher ui=$ui " +
            "deviceProfile=$deviceProfile output=$output clipboard=$clipboard " +
            "location=$location unsupportedMedia=$unsupportedMedia regressions=$regressions"
    }
}
