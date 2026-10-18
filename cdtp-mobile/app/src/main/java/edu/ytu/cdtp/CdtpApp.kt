package edu.ytu.cdtp

import android.app.Application
import edu.ytu.cdtp.camera.CameraController
import edu.ytu.cdtp.report.RecordRepository
import edu.ytu.cdtp.report.db.AppDatabase
import edu.ytu.cdtp.task.TestController
import edu.ytu.cdtp.telemetry.BleScanner
import edu.ytu.cdtp.telemetry.TelemetryRepository
import edu.ytu.cdtp.vision.VisionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Basit elle bağımlılık enjeksiyonu: uygulama ömrü boyunca tek örnekler. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val telemetry = TelemetryRepository(app, scope) { settings.evalConfig.value.linkTimeoutMs }
    val bleScanner = BleScanner(app)
    val vision = VisionPipeline(settings, telemetry)
    val db = AppDatabase.build(app)
    val records = RecordRepository(db)
    @Volatile var camera: CameraController? = null
    val tests = TestController(app, scope, settings, telemetry, vision, records) { camera }
}

class CdtpApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
