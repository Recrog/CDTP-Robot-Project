package edu.ytu.cdtp.report

import edu.ytu.cdtp.report.db.TestRecordEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * recordHash = SHA256(prevHash + payloadJson). İlk kaydın prevHash'i 64 sıfırdır.
 * Tek bir byte değişirse o kayıttan itibaren zincir kırılır.
 */
object HashChain {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun recordHash(prevHash: String, payloadJson: String): String =
        sha256Hex((prevHash + payloadJson).toByteArray(Charsets.UTF_8))

    fun fileSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    data class Issue(val recordId: Long, val index: Long, val problem: String, val fatal: Boolean = true)

    data class Report(val total: Int, val issues: List<Issue>) {
        val ok: Boolean get() = issues.none { it.fatal }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Kayıtlar id sırasıyla verilmeli. Kontroller:
     * hash doğru mu, prevHash bir öncekine bağlı mı, sıra numarası ardışık mı,
     * tablo sütunları (geçti/kaldı, görev) payload ile aynı mı, video hash'i tutuyor mu.
     */
    fun verify(records: List<TestRecordEntity>, checkVideo: Boolean = true): Report {
        val issues = ArrayList<Issue>()
        var expectedPrev = GENESIS
        var expectedIndex = 1L
        for (r in records) {
            val computed = recordHash(r.prevHash, r.payloadJson)
            if (computed != r.recordHash) issues += Issue(r.id, r.idx, "Hash uyuşmuyor: kayıt içeriği değiştirilmiş")
            if (r.prevHash != expectedPrev) issues += Issue(r.id, r.idx, "Zincir bağı kopuk: önceki kayıt silinmiş/değiştirilmiş")
            if (r.idx != expectedIndex) issues += Issue(r.id, r.idx, "Sıra numarası atlıyor (beklenen #$expectedIndex)")
            val p = runCatching { json.decodeFromString<RecordPayload>(r.payloadJson) }.getOrNull()
            if (p == null) {
                issues += Issue(r.id, r.idx, "Kayıt içeriği okunamıyor")
            } else {
                if (p.passed != r.passed) issues += Issue(r.id, r.idx, "GEÇTİ/KALDI sütunu içerikle çelişiyor")
                if (p.taskType != r.taskType || p.index != r.idx || p.prevHash != r.prevHash) {
                    issues += Issue(r.id, r.idx, "Tablo sütunları içerikle çelişiyor")
                }
                val v = p.video
                if (checkVideo && v != null) {
                    val f = File(v.path)
                    if (!f.exists()) issues += Issue(r.id, r.idx, "Video dosyası bulunamadı (${v.fileName})", fatal = false)
                    else if (fileSha256(f) != v.sha256) issues += Issue(r.id, r.idx, "Video dosyası değiştirilmiş (SHA-256 farklı)")
                }
            }
            expectedPrev = r.recordHash
            expectedIndex = r.idx + 1
        }
        return Report(records.size, issues)
    }
}
