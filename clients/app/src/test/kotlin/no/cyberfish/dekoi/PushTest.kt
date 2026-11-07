package no.cyberfish.dekoi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun theOwnerIsAskedOnceAfterLinkingADeKoiOnAndroid13AndLater() {
        assertTrue(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = false, hasDevice = true))
        assertFalse(shouldAskForNotifications(sdk = 32, granted = false, askedBefore = false, hasDevice = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = true, askedBefore = false, hasDevice = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = true, hasDevice = true))
        assertFalse(shouldAskForNotifications(sdk = 33, granted = false, askedBefore = false, hasDevice = false))
    }
}
