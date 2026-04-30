package dev.jongwoo.androidvm.vm

/**
 * Phase E.1 process-slot subclasses for [VmInstanceService]. Android requires a distinct
 * `<service>` component per `android:process` slot; the subclasses inherit all behaviour and
 * exist purely to give the manifest unique class names.
 */
class VmInstanceService2 : VmInstanceService()
class VmInstanceService3 : VmInstanceService()
class VmInstanceService4 : VmInstanceService()
