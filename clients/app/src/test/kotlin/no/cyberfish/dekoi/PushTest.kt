package no.cyberfish.dekoi

import no.cyberfish.dekoi.shared.DesignTokens
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RGB = 0xFFFFFF

class PushTest {
    // Building real FirebaseOptions needs the Android framework, so only the
    // switched-off cases run here.
    @Test
    fun pushIsOffUnlessEveryFirebaseSettingIsPresent() {
        assertNull(firebaseOptions("", "", "", ""))
        assertNull(firebaseOptions("dekoi", "1:2:android:3", "key", " "))
        assertNull(firebaseOptions("dekoi", "", "key", "2"))
    }

    @Test
    fun aMessageCanOnlyUseTheChannelsTheAppCreates() {
        assertEquals(URGENT_CHANNEL, channelFor("alerts_urgent"))
        assertEquals(UPDATES_CHANNEL, channelFor("updates"))
        assertEquals(ALERT_CHANNEL, channelFor("something_else"))
        assertEquals(ALERT_CHANNEL, channelFor(null))
    }

    @Test
    fun eachChannelHasTheColourOfItsSeverity() {
        assertEquals(DesignTokens.CRITICAL.color.toInt(), notificationColor(URGENT_CHANNEL))
        assertEquals(DesignTokens.WARNING.color.toInt(), notificationColor(ALERT_CHANNEL))
        assertEquals(DesignTokens.INFO.color.toInt(), notificationColor(UPDATES_CHANNEL))
    }

    @Test
    fun notifySendsTheSameColoursForTheNotificationsThePhoneDraws() {
        // Unit tests run in clients/app.
        val notify = File("../../backend/supabase/functions/notify/notify.ts").readText()
        val sent =
            Regex("""channel: "(\w+)",\s*priority: "\w+",\s*color: "#([0-9a-f]{6})"""")
                .findAll(notify)
                .associate { it.groupValues[1] to it.groupValues[2] }
        val expected = listOf(URGENT_CHANNEL, ALERT_CHANNEL, UPDATES_CHANNEL).associateWith { "%06x".format(notificationColor(it) and RGB) }
        assertEquals(expected, sent)
    }

    @Test
    fun notificationsAreTaggedByEvent() {
        assertEquals("event-42", notificationTag(42))
    }

    @Test
    fun onlyAPositiveEventIdOpensAnEvent() {
        assertEquals(42L, eventIdFrom("42"))
        assertNull(eventIdFrom(null))
        assertNull(eventIdFrom(""))
        assertNull(eventIdFrom("0"))
        assertNull(eventIdFrom("-5"))
        assertNull(eventIdFrom("42; drop"))
        assertNull(eventIdFrom("99999999999999999999"))
    }

    @Test
    fun theOwnerIsAskedOnceADeKoiIsSetUpOnAndroid13AndLater() {
        assertTrue(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = false, deviceSetUp = true))
        assertFalse(shouldAskForNotifications(sdk = 32, granted = false, askedBefore = false, deviceSetUp = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = true, askedBefore = false, deviceSetUp = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = true, deviceSetUp = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = false, deviceSetUp = false))
    }
}
