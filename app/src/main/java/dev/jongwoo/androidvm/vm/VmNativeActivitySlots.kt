package dev.jongwoo.androidvm.vm

/**
 * Phase E.1 process-slot subclasses. Android requires a distinct `<activity>` component class
 * per `android:process` declaration, so we expose three thin subclasses for slots `:vm2`/`:vm3`
 * /`:vm4`. The actual logic stays in [VmNativeActivity]; the subclasses only exist to give the
 * manifest a unique class name per slot.
 */
class VmNativeActivity2 : VmNativeActivity()
class VmNativeActivity3 : VmNativeActivity()
class VmNativeActivity4 : VmNativeActivity()
