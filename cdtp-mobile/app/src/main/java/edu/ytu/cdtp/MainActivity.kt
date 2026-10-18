package edu.ytu.cdtp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import edu.ytu.cdtp.ui.AppNav
import edu.ytu.cdtp.ui.theme.CdtpTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cvOk = OpenCVLoader.initLocal()
        // Tripod üzerinde test sırasında ekran kararmasın.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CdtpTheme {
                AppNav(openCvOk = cvOk)
            }
        }
    }
}
