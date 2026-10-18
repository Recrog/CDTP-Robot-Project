package edu.ytu.cdtp.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import edu.ytu.cdtp.report.db.TestRecordEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A4 PDF: karar, kurallar, ölçümler, yörünge, zaman çizelgesi, hash'ler. */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))
    fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

    fun export(ctx: Context, e: TestRecordEntity, p: RecordPayload): File {
        val doc = PdfDocument()
        val w = Writer(doc)

        w.text("ÇDTP – Mobil Görsel Ölçüm ve Değerlendirme Raporu", 15f, bold = true, color = 0xFF172430.toInt())
        w.text("YTÜ EEF · İki Tekerlekli Ev İçi Hasta Destek Robotu · Modül D", 9f, color = Color.DKGRAY)
        w.gap(6f)
        w.kv("Kayıt", "#${p.index}  (veritabanı id ${e.id})")
        w.kv("Görev", p.taskTitle)
        w.kv("Başlangıç", fmtDate(p.startWallMs))
        w.kv("Bitiş", "${fmtDate(p.endWallMs)}  (süre ${"%.1f".format(p.durationMs / 1000.0)} s)")
        w.kv("Bitiş nedeni", p.endReason)
        w.kv("Cihaz / uygulama", "${p.device} / ${p.app}")
        w.gap(6f)
        w.banner(if (p.passed) "GEÇTİ" else "KALDI", if (p.passed) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())

        w.section("Kurallar")
        p.rules.forEach { r -> w.bullet((if (r.passed) "✓ " else "✗ ") + r.name, r.detail, if (r.passed) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()) }

        w.section("Ölçülen değerler")
        p.measured.forEach { w.kv(it.key, it.value) }

        w.section("Bağlantı (telemetri)")
        w.kv("Tür", p.link.kind)
        w.kv("Paket / kayıp", "${p.link.packets} / ${p.link.lost}")
        w.kv("Ortalama periyot", "${"%.0f".format(p.link.meanPeriodMs)} ms")
        w.kv("En büyük aralık", "${p.link.maxGapMs} ms")
        w.kv("Kopma (> 2 s)", "${p.link.disconnects}")

        if (p.track.any { it.x != null }) {
            w.section("Yörünge (zemin koordinatı, m)")
            w.trajectory(p)
        }

        w.section("Zaman çizelgesi")
        p.events.take(80).forEach { ev -> w.small("+${"%.1f".format(ev.tRelMs / 1000.0)} s  [${ev.type}] ${ev.msg}") }
        if (p.events.size > 80) w.small("… ${p.events.size - 80} olay daha (JSON'da)")

        w.section("Bütünlük")
        w.kv("Değerlendirme ayarı", "SHA-256 ${p.evalConfigHash.take(16)}…  ${if (p.evalConfigFrozen) "(DONDURULMUŞ)" else "(dondurulmamış)"}")
        p.calibration?.let { w.kv("Kalibrasyon", "${it.floorW} × ${it.floorH} m, ${fmtDate(it.createdWallMs)}") }
        p.video?.let {
            w.kv("Video", "${it.fileName} (${it.sizeBytes / 1024} KB)")
            w.mono("video SHA-256: ${it.sha256}")
        } ?: w.kv("Video", "yok — zaman damgalı kayıt kullanıldı")
        w.mono("prevHash:   ${e.prevHash}")
        w.mono("recordHash: ${e.recordHash}")
        w.small("recordHash = SHA256(prevHash + payloadJson). Kayıt uygulamada değiştirilemez/silinemez; tek bir byte değişirse \"Zinciri doğrula\" kırılmayı gösterir.")

        w.finish()
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, "cdtp_rapor_${p.index}_${p.taskType.lowercase()}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private class Writer(val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private lateinit var c: Canvas
        private var y = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { newPage() }

        private fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p
            c = p.canvas
            y = M
            paint.reset(); paint.isAntiAlias = true
            paint.textSize = 8f; paint.color = Color.GRAY
            c.drawText("ÇDTP Modül D raporu · sayfa $pageNo", M, H - 20f, paint)
        }

        private fun ensure(h: Float) { if (y + h > H - M) newPage() }

        private fun setup(size: Float, bold: Boolean, color: Int, mono: Boolean = false) {
            paint.textSize = size
            paint.color = color
            paint.typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
        }

        private fun wrap(s: String, maxW: Float): List<String> {
            val out = ArrayList<String>()
            for (para in s.split("\n")) {
                var line = ""
                for (word in para.split(" ")) {
                    val cand = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(cand) <= maxW) line = cand
                    else {
                        if (line.isNotEmpty()) out += line
                        var wd = word
                        while (paint.measureText(wd) > maxW && wd.length > 1) {
                            var k = wd.length
                            while (k > 1 && paint.measureText(wd.substring(0, k)) > maxW) k--
                            out += wd.substring(0, k); wd = wd.substring(k)
                        }
                        line = wd
                    }
                }
                out += line
            }
            return out
        }

        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK, x: Float = M, mono: Boolean = false) {
            setup(size, bold, color, mono)
            for (l in wrap(s, W - M - x)) {
                ensure(size * 1.4f)
                y += size * 1.25f
                c.drawText(l, x, y, paint)
            }
        }

        fun gap(h: Float) { y += h }

        fun section(title: String) {
            ensure(40f)
            y += 10f
            text(title, 11.5f, bold = true, color = 0xFF1D6A72.toInt())
            paint.color = 0xFF1D6A72.toInt(); paint.strokeWidth = 0.8f
            c.drawLine(M, y + 3f, W - M, y + 3f, paint)
            y += 6f
        }

        fun kv(k: String, v: String) {
            setup(9.5f, true, Color.DKGRAY)
            ensure(14f)
            val keyW = 150f
            y += 12f
            c.drawText(k, M, y, paint)
            setup(9.5f, false, Color.BLACK)
            val lines = wrap(v, W - 2 * M - keyW)
            lines.forEachIndexed { i, l ->
                if (i > 0) { ensure(12f); y += 12f }
                c.drawText(l, M + keyW, y, paint)
            }
        }

        fun bullet(title: String, detail: String, color: Int) {
            text(title, 10f, bold = true, color = color)
            text(detail, 9f, color = Color.DKGRAY, x = M + 14f)
        }

        fun small(s: String) = text(s, 8.5f, color = Color.DKGRAY)
        fun mono(s: String) = text(s, 7.5f, color = Color.BLACK, mono = true)

        fun banner(s: String, color: Int) {
            ensure(50f)
            paint.color = color
            c.drawRoundRect(M, y, W - M, y + 42f, 8f, 8f, paint)
            setup(24f, true, Color.WHITE)
            c.drawText(s, M + 16f, y + 31f, paint)
            y += 48f
        }

        fun trajectory(p: RecordPayload) {
            val pts = p.track.filter { it.x != null && it.y != null }
            val boxH = 220f
            ensure(boxH + 10f)
            val cal = p.calibration
            var minX = pts.minOf { it.x!! }; var maxX = pts.maxOf { it.x!! }
            var minY = pts.minOf { it.y!! }; var maxY = pts.maxOf { it.y!! }
            if (cal != null) {
                minX = minOf(minX, 0.0); minY = minOf(minY, 0.0)
                maxX = maxOf(maxX, cal.floorW); maxY = maxOf(maxY, cal.floorH)
                cal.station?.let { minX = minOf(minX, it.x); maxX = maxOf(maxX, it.x); minY = minOf(minY, it.y); maxY = maxOf(maxY, it.y) }
            }
            val pad = 0.1
            minX -= pad; minY -= pad; maxX += pad; maxY += pad
            val areaW = W - 2 * M
            val s = minOf(areaW / (maxX - minX), boxH / (maxY - minY)).toFloat()
            val top = y + 4f
            fun px(x: Double) = M + ((x - minX) * s).toFloat()
            fun py(yy: Double) = top + boxH - ((yy - minY) * s).toFloat() // y yukarı
            paint.style = Paint.Style.STROKE
            paint.color = Color.LTGRAY; paint.strokeWidth = 0.8f
            c.drawRect(M, top, M + areaW, top + boxH, paint)
            if (cal != null) {
                paint.color = 0xFF1D6A72.toInt()
                c.drawRect(px(0.0), py(cal.floorH), px(cal.floorW), py(0.0), paint)
            }
            paint.color = 0xFFE8765A.toInt(); paint.strokeWidth = 1.6f
            for (i in 1 until pts.size) c.drawLine(px(pts[i - 1].x!!), py(pts[i - 1].y!!), px(pts[i].x!!), py(pts[i].y!!), paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF2E7D32.toInt(); c.drawCircle(px(pts.first().x!!), py(pts.first().y!!), 4f, paint)
            paint.color = 0xFFC62828.toInt(); c.drawCircle(px(pts.last().x!!), py(pts.last().y!!), 4f, paint)
            cal?.station?.let { st ->
                paint.color = 0xFF172430.toInt()
                c.drawRect(px(st.x) - 4f, py(st.y) - 4f, px(st.x) + 4f, py(st.y) + 4f, paint)
            }
            y = top + boxH + 4f
            small("Yeşil: başlangıç · Kırmızı: son konum · Kare: şarj istasyonu · Çerçeve: zemin kalibrasyon alanı")
        }

        fun finish() { page?.let { doc.finishPage(it) }; page = null }
    }
}
