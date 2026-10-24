package dev.bridge.assistant

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bridge.assistant.assistant.BridgeSessionService
import dev.bridge.assistant.assistant.BridgeVoiceService
import dev.bridge.assistant.assistant.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestInstrumentedTest {
    @Test fun assistantAndMicrophoneServicesAreDeclaredWithPlatformGuards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val voice = pm.getServiceInfo(ComponentName(context, BridgeVoiceService::class.java), 0)
        val session = pm.getServiceInfo(ComponentName(context, BridgeSessionService::class.java), 0)
        val wake = pm.getServiceInfo(ComponentName(context, WakeWordService::class.java), 0)

        assertTrue(voice.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", voice.permission)
        assertTrue(session.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", session.permission)
        assertTrue(!wake.exported)
        if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(wake.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        }
        val permissions = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(permissions.contains(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(permissions.contains("dev.bridge.assistant.permission.INTERNAL_BROADCAST"))
    }

    @Test fun voiceInteractionMetadataPointsAtSessionServiceAndAppIsChabi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val app = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals("Chabi", pm.getApplicationLabel(app).toString())
        val voice = pm.getServiceInfo(ComponentName(context, BridgeVoiceService::class.java), PackageManager.GET_META_DATA)
        val metadataId = voice.metaData?.getInt("android.voice_interaction") ?: 0
        assertTrue(metadataId != 0)
        val xml = context.resources.getXml(metadataId)
        try {
            var foundSessionService = false
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "voice-interaction-service") {
                    foundSessionService = xml.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "sessionService"
                    ) == "dev.bridge.assistant.assistant.BridgeSessionService"
                    break
                }
                xml.next()
            }
            assertTrue("Voice session service resource is missing", foundSessionService)
        } finally { xml.close() }
    }
}
