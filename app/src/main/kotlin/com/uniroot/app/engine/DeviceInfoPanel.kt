package com.uniroot.app.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Real device information for the home screen — everything readable WITHOUT
 * any privilege (plus the root-state block that uses grant-independent
 * signals so it also works rooted-but-no-su).
 */
object DeviceInfoPanel {

    data class Rootability(
        val rootable: Boolean,
        val summary: String,
        val detail: String,
    )

    private val SAMSUNG_NAMES = mapOf(
        "SM-S931" to "Galaxy S25", "SM-S936" to "Galaxy S25+", "SM-S937" to "Galaxy S25 Edge",
        "SM-S938" to "Galaxy S25 Ultra",
        "SM-S948" to "Galaxy S26 Ultra", "SM-S946" to "Galaxy S26", "SM-S943" to "Galaxy S26 Edge",
        "SM-F976" to "Galaxy Z Fold 8", "SM-F971" to "Galaxy Z Fold 8",
        "SM-F966" to "Galaxy Z Fold 7", "SM-F956" to "Galaxy Z Fold 6",
        "SM-F766" to "Galaxy Z Flip 7", "SM-F741" to "Galaxy Z Flip 6", "SM-F736" to "Galaxy Z Flip 7",
        "SM-A366" to "Galaxy A56", "SM-A556" to "Galaxy A55", "SM-A546" to "Galaxy A54",
        "SM-A356" to "Galaxy A36", "SM-A346" to "Galaxy A35",
        "SM-E736" to "Galaxy E56", "SM-S921" to "Galaxy S24", "SM-S928" to "Galaxy S24 Ultra",
        "SM-S926" to "Galaxy S24+",
    )

    private val BUNDLED_DF_KMIS = setOf(
        "android12-5.10", "android13-5.10", "android13-5.15", "android14-5.15",
        "android14-6.1", "android15-6.6", "android16-6.12", "android17-6.18",
    )

    internal fun marketingNameFor(model: String, fallbackDevice: String = "?"): String {
        SAMSUNG_NAMES.forEach { (k, v) -> if (model.startsWith(k)) return "$v ($model)" }
        return model.ifBlank { fallbackDevice.ifBlank { "?" } }
    }

    fun marketingName(): String = marketingNameFor(Build.MODEL ?: "", Build.DEVICE ?: "?")

    private fun prop(name: String): String? = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    private fun fmtBytes(b: Long): String {
        val gb = b / (1024.0 * 1024 * 1024)
        return if (gb >= 10) String.format("%.0f GB", gb) else String.format("%.1f GB", gb)
    }

    /**
     * Kernel KMI parsed from a Linux release string:
     * - "6.6.127-android15-8-peeee058-abogkiF966USQUCCZIH-4k" -> "android15-6.6"
     * - "android15-6.6" -> "android15-6.6"
     */
    internal fun kmiFromRelease(release: String): String? {
        Regex("(\\d+\\.\\d+)\\.\\d+-android(\\d+)").find(release)?.let { m ->
            return "android${m.groupValues[2]}-${m.groupValues[1]}"
        }
        Regex("android(\\d+)-(\\d+\\.\\d+)").find(release)?.let { m ->
            return "android${m.groupValues[1]}-${m.groupValues[2]}"
        }
        return null
    }

    /** Kernel KMI parsed from the running kernel release string (e.g. android15-6.6). */
    fun kmi(): String? = kmiFromRelease(kernelRelease())

    fun kernelRelease(): String = System.getProperty("os.version") ?: Build.VERSION.INCREMENTAL ?: "?"

    fun is64Bit(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    internal fun assessDirtyFragRootability(
        is64Bit: Boolean,
        kmi: String?,
        bundledKmis: Set<String> = BUNDLED_DF_KMIS,
    ): Rootability = when {
        !is64Bit -> Rootability(
            rootable = false,
            summary = "No — 64-bit ARM required",
            detail = "Not rootable — DirtyFrag requires a 64-bit ARM kernel",
        )
        kmi != null && kmi in bundledKmis -> Rootability(
            rootable = true,
            summary = "Yes — DirtyFrag ($kmi)",
            detail = "Rootable via DirtyFrag (KMI: $kmi)",
        )
        kmi != null -> Rootability(
            rootable = false,
            summary = "No — unsupported KMI ($kmi)",
            detail = "Not rootable — no bundled DirtyFrag module for $kmi",
        )
        else -> Rootability(
            rootable = false,
            summary = "Unknown — non-GKI kernel",
            detail = "Could not detect GKI KMI from kernel release",
        )
    }

    fun bundledDirtyFragKmis(context: Context): Set<String> {
        val fromAssets = runCatching {
            context.assets.list("df/ghostsam/payloads/dirtyfrag")
                ?.mapNotNull { name ->
                    if (name.startsWith("dfr_lkm-") && name.endsWith(".ko")) {
                        name.removePrefix("dfr_lkm-").removeSuffix(".ko")
                    } else null
                }?.toSet()
        }.getOrNull().orEmpty()
        return if (fromAssets.isNotEmpty()) fromAssets else BUNDLED_DF_KMIS
    }

    fun dirtyFragRootability(context: Context): Rootability =
        assessDirtyFragRootability(is64Bit(), kmi(), bundledDirtyFragKmis(context))

    fun oneUiVersion(): String? = prop("ro.build.version.oneui")?.let { v ->
        // Samsung encodes e.g. 80100 -> 8.1.0
        if (v.length >= 5) "${v[0]}.${v[1]}.${v[2]}" else v
    }

    fun knoxState(): String? {
        val warranty = prop("ro.boot.warranty_bit") ?: prop("ro.warranty_bit") ?: return null
        val knox = prop("ro.boot.warrantybit") ?: prop("knox") ?: warranty
        return if (warranty == "1" || knox == "1") "Tripped (0x1)" else "Intact (0x0)"
    }

    /** All non-privileged info rows, ready for the home card. */
    fun rows(context: Context): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Device" to marketingName()
        rows += "SoC" to listOfNotNull(
            runCatching { Build.SOC_MANUFACTURER }.getOrNull(),
            runCatching { Build.SOC_MODEL }.getOrNull(),
        ).joinToString(" ").ifBlank { Build.HARDWARE ?: "?" }
        rows += "CPU" to "${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · ${if (is64Bit()) "64-bit" else "32-bit"}"
        rows += "Android" to listOfNotNull(
            Build.VERSION.RELEASE,
            "patch ${Build.VERSION.SECURITY_PATCH}",
            oneUiVersion()?.let { "One UI $it" },
        ).joinToString(" · ")
        rows += "Kernel" to listOfNotNull(kernelRelease(), kmi()).joinToString(" · ")
        rows += "Rootable" to dirtyFragRootability(context).summary
        rows += "Fingerprint" to (Build.FINGERPRINT ?: Build.DISPLAY ?: "?")
        val am = context.getSystemService(ActivityManager::class.java)
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            rows += "RAM" to "${fmtBytes(mi.totalMem)} (${fmtBytes(mi.availMem)} free)"
        }
        runCatching {
            val st = StatFs(Environment.getDataDirectory().absolutePath)
            val total = st.totalBytes
            val free = st.availableBytes
            rows += "Storage" to "${fmtBytes(total - free)} used of ${fmtBytes(total)}"
        }
        runCatching {
            val wm = context.getSystemService(android.view.WindowManager::class.java) ?: return@runCatching
            val m = wm.maximumWindowMetrics
            val b = m.bounds
            val refresh = context.display.refreshRate
            rows += "Display" to "${b.width()}×${b.height()} @ ${"%.0f".format(refresh)} Hz"
        }
        runCatching {
            val batt = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batt != null) {
                val level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val pct = level * 100 / scale
                    val status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val state = when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        BatteryManager.BATTERY_STATUS_FULL -> "full"
                        else -> "on battery"
                    }
                    rows += "Battery" to "$pct% ($state)"
                }
            }
        }
        knoxState()?.let { rows += "Knox warranty" to it }
        rows += "Uptime" to fmtUptime(android.os.SystemClock.elapsedRealtime())
        return rows
    }

    private fun fmtUptime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /**
     * Live root state for the current kernel boot:
     * 1. Working `su -c id` (uid=0) -> confirmed Rooted (and stamped for this boot_id)
     * 2. Same kernel boot_id as a successful UniRoot run -> Rooted (persists
     *    across zygote / surfaceflinger soft reboots even when UniRoot itself
     *    has not been granted su)
     * 3. Otherwise Not rooted.
     */
    fun rootState(context: Context? = null): Triple<String, String, String> {
        val su = runCatching {
            val p = ProcessBuilder("su", "-c", "id").start()
            val done = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { runCatching { p.destroy() }; "" }
            else p.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (su.contains("uid=0")) {
            if (context != null) BootRootState.markRooted(context)
            return Triple("Rooted", "", "su uid=0 — KernelSU active")
        }
        if (context != null && BootRootState.isRootedThisBoot(context)) {
            return Triple("Rooted", "", "Active this boot (persists across soft reboot)")
        }
        val modules = runCatching { File("/proc/modules").readText() }.getOrDefault("")
        return when {
            modules.contains("kernelsu", ignoreCase = true) ->
                Triple("Not rooted", "", "module loaded but su is down — run Root again")
            else -> Triple("Not rooted", "", "no KernelSU module loaded")
        }
    }

    fun flavor(context: Context): String {
        val next = KsuManagerDetector.find(context, next = true)
        val classic = KsuManagerDetector.find(context, next = false)
        return when {
            next != null && classic != null -> "KernelSU Next + classic installed"
            next != null -> if (next.spoofed) "KernelSU Next (spoofed)" else "KernelSU Next"
            classic != null -> if (classic.spoofed) "KernelSU (spoofed)" else "KernelSU"
            else -> "no manager installed"
        }
    }
}
