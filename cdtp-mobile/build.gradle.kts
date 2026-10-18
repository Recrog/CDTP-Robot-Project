plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// Windows'ta proje yolunda ASCII olmayan karakter ("Ç") varsa birim test çalıştırıcısı
// sınıfları bulamıyor. Bu durumda derleme çıktıları ~/.cdtp-build altına yönlendirilir.
// APK: ~/.cdtp-build/app/outputs/apk/debug/app-debug.apk
if (rootDir.absolutePath.any { it.code > 127 }) {
    allprojects {
        layout.buildDirectory.set(file("${System.getProperty("user.home")}/.cdtp-build/${project.name}"))
    }
}
