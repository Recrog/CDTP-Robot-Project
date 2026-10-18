package edu.ytu.cdtp.task

import kotlinx.serialization.Serializable

enum class TaskType(val title: String, val module: String, val metric: String) {
    STATIC_BALANCE("Statik Denge", "Modül A", "30 s dengede kalmak"),
    GUIDANCE("Robot Yönlendirmesi", "Modül B", "Doğru yön ve kat edilen mesafe"),
    CHARGING("Şarj Etme", "Modül C", "Şarj başlangıcının 5 s içinde tespiti"),
    INTEGRATED("Bütünleşik Görev", "Modül E", "İlaç kutusunun başarılı biçimde taşınması"),
    RECOVERY("Toparlanma (±10°)", "Ö1.4 – ek", "10 s içinde dik konuma dönmek"),
}

enum class MotionState(val label: String) {
    STATIC("STATİK DENGE"),
    MOVING("HAREKET"),
    TURNING("DÖNÜŞ"),
    RECOVERING("TOPARLANMA"),
    FALLEN("DEVRİLDİ"),
    NOT_VISIBLE("GÖRÜNMÜYOR"),
}

enum class Direction(val label: String) {
    NONE("—"),
    FORWARD("İLERİ"),
    BACKWARD("GERİ"),
    LEFT("SOL"),
    RIGHT("SAĞ"),
}

enum class BoxStatus(val label: String) {
    ON_ROBOT("ROBOTTA"),
    DROPPED("DÜŞTÜ"),
    UNKNOWN("BELİRSİZ"),
}

enum class ChargeMode(val title: String) {
    BASIC("Temel (yerleş → 5 s içinde şarj)"),
    UNPLUG_REPLUG("Çek / geri getir (Ö3.4)"),
}

@Serializable
data class TaskSpec(
    val type: TaskType,
    val expectedDirection: Direction? = null,
    val minDistM: Double? = null,
    val maxDistM: Double? = null,
    val chargeMode: ChargeMode = ChargeMode.BASIC,
) {
    val title: String
        get() = buildString {
            append(type.title)
            if (type == TaskType.GUIDANCE && expectedDirection != null) {
                append(" – ").append(expectedDirection.label)
                if (expectedDirection == Direction.FORWARD || expectedDirection == Direction.BACKWARD) {
                    append(" (").append(minDistM).append("–").append(maxDistM).append(" m)")
                }
            }
            if (type == TaskType.CHARGING && chargeMode == ChargeMode.UNPLUG_REPLUG) append(" – çek/geri getir")
        }

    fun options(): Map<String, String> = buildMap {
        expectedDirection?.let { put("beklenenYon", it.name) }
        minDistM?.let { put("minMesafeM", it.toString()) }
        maxDistM?.let { put("maxMesafeM", it.toString()) }
        if (type == TaskType.CHARGING) put("sarjModu", chargeMode.name)
    }
}
