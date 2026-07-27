package io.github.kingg22.godot.internal.binding

import io.github.kingg22.godot.internal.ffi.GDExtensionClassLibraryPtr
import io.github.kingg22.godot.internal.ffi.GDExtensionInterfaceGetProcAddress

public class BindingProcAddressHolder private constructor(
    public val getProcAddress: GDExtensionInterfaceGetProcAddress,
    public val library: GDExtensionClassLibraryPtr,
) {
    public companion object {
        private var instance: BindingProcAddressHolder? = null

        public val isInitialized: Boolean get() = instance != null

        /** Shortcut to [BindingProcAddressHolder.library] of [instance] */
        public val library: GDExtensionClassLibraryPtr get() = instance().library

        /** Shortcut to [BindingProcAddressHolder.getProcAddress] of [instance] */
        public val getProcAddress: GDExtensionInterfaceGetProcAddress get() = instance().getProcAddress

        /** Retrieve the current instance or throws [IllegalStateException] if [instance] is null */
        public fun instance(): BindingProcAddressHolder = requireNotNull(instance) {
            "GDExtension getProcAddress holder is not initialized. Call initialize() first."
        }

        public fun instanceOrNull(): BindingProcAddressHolder? = instance

        /** Initialize the holder with the given [getProcAddress] and [libraryPtr] */
        public fun initialize(
            getProcAddress: GDExtensionInterfaceGetProcAddress,
            libraryPtr: GDExtensionClassLibraryPtr,
        ) {
            instance = BindingProcAddressHolder(getProcAddress, libraryPtr)
        }

        /** Reset the holder to null */
        public fun reset() {
            instance = null
        }
    }
}
