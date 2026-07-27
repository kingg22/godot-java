package io.github.kingg22.godot.codegen.models.extensionapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A top-level entry from Godot's `global_constants` API section.
 *
 * As of Godot 4.7 this section only carries fixed-width integer limits (e.g. `UINT8_MAX`,
 * `INT64_MIN`), mirroring C's `<cstdint>` limits — everything else Godot exposes globally
 * lives in `global_enums` instead. See [io.github.kingg22.godot.codegen.extensionapi.impl.knative.generators.NativeGlobalConstantGenerator].
 */
@Serializable
class GlobalConstant(
    override val name: String,
    override val value: Long,
    @SerialName("is_bitfield") val isBitfield: Boolean = false,
    override val description: String? = null,
) : ConstantDescriptor<Long>
