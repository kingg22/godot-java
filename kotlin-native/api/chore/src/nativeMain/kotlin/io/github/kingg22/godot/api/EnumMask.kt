@file:Suppress("NOTHING_TO_INLINE")

package io.github.kingg22.godot.api

import io.github.kingg22.godot.api.internal.UsedFromCodegenGeneratedCode

/**
 * Value class for bitfield masks.
 * Allows bitwise operations on enum flags.
 *
 * @param T The type of the enum flags.
 * @property flags The bitfield value.
 * @constructor Creates a new EnumMask instance. Prefers extension and companion methods.
 */
@UsedFromCodegenGeneratedCode
public value class EnumMask<T> @ExperimentalGodotKotlin constructor(public val flags: Long) :
    GodotEnum
    where T : GodotEnum, T : Enum<T> {
    override val value: Long get() = flags

    /** Bitwise OR between both enums */
    public inline infix fun or(other: T): EnumMask<T> = EnumMask(flags or other.value)

    /** Bitwise OR: combines masks */
    public inline infix fun or(other: EnumMask<T>): EnumMask<T> = EnumMask(flags or other.flags)

    /** Bitwise AND: flag intersection */
    public inline infix fun and(other: T): EnumMask<T> = EnumMask(flags and other.value)

    /** Bitwise AND: mask intersection */
    public inline infix fun and(other: EnumMask<T>): EnumMask<T> = EnumMask(flags and other.flags)

    /** Bitwise XOR */
    public inline infix fun xor(other: T): EnumMask<T> = EnumMask(flags xor other.value)

    /** Bitwise XOR */
    public inline infix fun xor(other: EnumMask<T>): EnumMask<T> = EnumMask(flags xor other.flags)

    /** Inverts all bits */
    public inline operator fun not(): EnumMask<T> = EnumMask(flags.inv())

    /** Checks if it contains a specific flag */
    public inline operator fun contains(flag: T): Boolean = (flags and flag.value) != 0L

    /** Checks if it contains all flags from another mask */
    public inline fun containsAll(other: EnumMask<T>): Boolean = (flags and other.flags) == other.flags

    /** Checks if it is empty */
    public inline fun isEmpty(): Boolean = flags == 0L

    /** Checks if it is not empty */
    public inline fun isNotEmpty(): Boolean = flags != 0L

    public companion object {
        /** Empty mask */
        public inline fun <T> of(): EnumMask<T> where T : GodotEnum, T : Enum<T> = EnumMask(0L)

        /** Creates a mask from a single enum */
        public inline fun <T> of(flag: T): EnumMask<T> where T : GodotEnum, T : Enum<T> = EnumMask(flag.value)

        /** Creates a mask from two enum */
        public inline fun <T> of(first: T, second: T): EnumMask<T> where T : GodotEnum, T : Enum<T> =
            EnumMask(first.value or second.value)

        /** Creates a mask from multiple enums */
        public inline fun <T> of(vararg flags: T): EnumMask<T> where T : GodotEnum, T : Enum<T> = of(flags.toList())

        /** Creates a mask from multiple enums */
        public inline fun <T> of(flags: Iterable<T>): EnumMask<T> where T : GodotEnum, T : Enum<T> =
            EnumMask(flags.fold(0L) { acc, flag -> acc or flag.value })
    }
}

/**
 * Performs a BITWISE OR between two enums
 *
 * @param other An enum entry of the same type as this one.
 * @return An [EnumMask] with this enum type and the result value of BITWISE OR operation.
 * @see EnumMask
 */
public inline infix fun <T> T.or(other: T): EnumMask<T> where T : GodotEnum, T : Enum<T> = EnumMask.of(this, other)

/**
 * Performs a BITWISE OR between enum and enum mask
 *
 * @param mask An enum mask of the same type as this one.
 * @return An [EnumMask] with this enum type and the result value of BITWISE OR operation.
 * @see EnumMask
 */
public inline infix fun <T> T.or(mask: EnumMask<T>): EnumMask<T> where T : GodotEnum, T : Enum<T> =
    EnumMask(this.value or mask.flags)

/** Converts [Array] of [enums][GodotEnum] to an [EnumMask] of the same type */
public inline fun <T> Array<out T>.toEnumMask(): EnumMask<T> where T : GodotEnum, T : Enum<T> =
    EnumMask.of(this.toList())

/** Converts [Iterable] of [enums][GodotEnum] to an [EnumMask] of the same type */
public inline fun <T> Iterable<T>.toEnumMask(): EnumMask<T> where T : GodotEnum, T : Enum<T> = EnumMask.of(this)
