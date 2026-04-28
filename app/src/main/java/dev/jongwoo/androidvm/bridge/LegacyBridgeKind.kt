package dev.jongwoo.androidvm.bridge

/**
 * Legacy bridge categorisation kept only for the bag-of-flags written into vm_config.json so the
 * native side does not see a schema change while Stage 07 migrates to the new bridge model.
 *
 * New code must use [BridgeType] instead. Once Stage 07 finishes the policy/audit migration this
 * type and [LegacyBridgePolicy] can be deleted along with the vm_config bridge flag block.
 */
enum class LegacyBridgeKind {
    AUDIO_OUTPUT,
    CLIPBOARD,
    CONTACTS,
    FILES,
    LOCATION,
    MICROPHONE,
    VIBRATION,
}
