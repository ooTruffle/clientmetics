package com.ootruffle.clientmetics.compat.compose

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads OneConfig's UI preferences (theme, accent, opacities, blur, animations) when OneConfig is
 * installed, so the wardrobe matches OneConfig's menu. Everything is looked up reflectively, so
 * the wardrobe never links against OneConfig: without it (or if a field moves), each read returns
 * its default.
 */
internal object OneConfigPrefs {
    private val holders = listOf(
        "org.polyfrost.oneconfig.internal.ThemeConfig",
        "org.polyfrost.oneconfig.internal.OneConfigConfig",
    )

    private val cache = ConcurrentHashMap<String, Optional<Field>>()

    private fun field(name: String): Field? =
        cache.computeIfAbsent(name) { Optional.ofNullable(lookup(it)) }.orElse(null)

    private fun lookup(name: String): Field? {
        for (holder in holders) {
            val cls = try {
                Class.forName(holder)
            } catch (_: Throwable) {
                continue
            }
            val f = try {
                cls.getField(name)
            } catch (_: Throwable) {
                continue
            }
            if (Modifier.isStatic(f.modifiers)) return f
        }
        return null
    }

    fun bool(name: String, default: Boolean): Boolean =
        try { field(name)?.getBoolean(null) ?: default } catch (_: Throwable) { default }

    fun float(name: String, default: Float): Float =
        try { field(name)?.getFloat(null) ?: default } catch (_: Throwable) { default }

    fun value(name: String): Any? =
        try { field(name)?.get(null) } catch (_: Throwable) { null }
}
