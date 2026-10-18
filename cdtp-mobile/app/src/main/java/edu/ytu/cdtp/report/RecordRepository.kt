package edu.ytu.cdtp.report

import androidx.room.withTransaction
import edu.ytu.cdtp.BuildConfig
import edu.ytu.cdtp.report.db.AppDatabase
import edu.ytu.cdtp.report.db.TestRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class RecordRepository(private val db: AppDatabase) {
    private val dao = db.records()

    fun observeAll() = dao.observeAll()
    suspend fun byId(id: Long) = dao.byId(id)

    /** Zincire yeni kayıt ekler (sıra no + prevHash aynı transaction'da alınır). */
    suspend fun append(build: (index: Long, prevHash: String) -> RecordPayload): TestRecordEntity =
        db.withTransaction {
            val last = dao.last()
            val index = (last?.idx ?: 0) + 1
            val prev = last?.recordHash ?: HashChain.GENESIS
            val payload = build(index, prev)
            val text = json.encodeToString(payload)
            val e = TestRecordEntity(
                idx = index,
                createdAtMs = payload.startWallMs,
                taskType = payload.taskType,
                taskTitle = payload.taskTitle,
                passed = payload.passed,
                payloadJson = text,
                prevHash = prev,
                recordHash = HashChain.recordHash(prev, text),
            )
            e.copy(id = dao.insert(e))
        }

    suspend fun verify(): HashChain.Report = withContext(Dispatchers.IO) {
        HashChain.verify(dao.allAscending())
    }

    /** Zinciri dışarıda (PC'de tools/verify_chain.py ile) doğrulamak için JSON. */
    suspend fun exportChain(dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val all = dao.allAscending()
        val arr = buildJsonArray {
            all.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("idx", r.idx)
                    put("taskType", r.taskType)
                    put("passed", r.passed)
                    put("prevHash", r.prevHash)
                    put("recordHash", r.recordHash)
                    put("payloadJson", r.payloadJson)
                })
            }
        }
        val f = File(dir, "cdtp_zincir_${System.currentTimeMillis()}.json")
        f.writeText(prettyJson.encodeToString(arr))
        f
    }

    /**
     * YALNIZ DEBUG: "DB dosyasını dışarıdan değiştir" testini telefonda göstermek için
     * son kaydı DAO'yu atlayarak ham SQL ile kurcalar. Release derlemede çalışmaz.
     */
    suspend fun debugTamperLast(): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext false
        val last = dao.last() ?: return@withContext false
        val flipped = !last.passed
        val newJson = last.payloadJson.replace("\"passed\":${last.passed}", "\"passed\":$flipped")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE test_records SET passed = ?, payloadJson = ? WHERE id = ?",
            arrayOf<Any>(if (flipped) 1 else 0, newJson, last.id),
        )
        true
    }

    companion object {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        private val lenientJson = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { prettyPrint = true }
        fun decode(e: TestRecordEntity): RecordPayload? = runCatching {
            lenientJson.decodeFromString<RecordPayload>(e.payloadJson)
        }.getOrNull()
    }
}
