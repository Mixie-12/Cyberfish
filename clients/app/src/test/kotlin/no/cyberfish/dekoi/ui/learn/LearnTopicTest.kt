package no.cyberfish.dekoi.ui.learn

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnTopicTest {
    // Unit tests run in clients/app.
    private val migrations = File("../../backend/supabase/migrations")
    private val strings = File("src/main/res/values/strings_learn.xml")

    /**
     * The kinds the events table accepts, from the latest migrations: the
     * kinds devices may send (private.device_event_kinds) and the ones the
     * events_kind_check constraint adds.
     */
    private fun backendEventKinds(): Set<String> {
        val sql =
            checkNotNull(migrations.listFiles { file -> file.name.endsWith(".sql") })
                .sortedBy { it.name }
                .joinToString("\n") { it.readText() }
        val function = Regex("""function private\.device_event_kinds\(\)[^$]*\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
        val check = Regex("""add constraint events_kind_check check \((.*?)\);""", RegexOption.DOT_MATCHES_ALL)
        val lists = listOf(function, check).map { regex -> regex.findAll(sql).last().groupValues[1] }
        return lists.flatMap { list -> Regex("'([a-z_]+)'").findAll(list).map { it.groupValues[1] } }.toSet()
    }

    @Test
    fun `every event kind the backend accepts has a topic`() {
        val kinds = backendEventKinds()
        assertTrue("login_attempt" in kinds && "device_offline" in kinds, "kinds read from the migrations: $kinds")
        for (kind in kinds) {
            assertNotNull(learnTopicFor(kind), kind)
        }
    }

    @Test
    fun `kinds decided for later already lead to their topic`() {
        assertEquals(LearnTopic.HONEYPOT, learnTopicFor("file_access"))
        assertEquals(LearnTopic.WIFI_COPY, learnTopicFor("evil_twin"))
        assertEquals(LearnTopic.NEW_DEVICE, learnTopicFor("new_host"))
        assertEquals(LearnTopic.PORT_FORWARD, learnTopicFor("port_forward"))
        assertEquals(LearnTopic.DNS_TAMPERING, learnTopicFor("dns_hijack"))
    }

    @Test
    fun `a kind from a newer backend has no topic`() {
        assertNull(learnTopicFor("something_new"))
    }

    @Test
    fun `topic ids never change`() {
        val ids =
            listOf(
                "honeypot",
                "sign_in",
                "port_scan",
                "network_sweep",
                "impersonation",
                "fake_settings",
                "wifi_copy",
                "new_device",
                "port_forward",
                "dns_tampering",
                "home_safety",
            )
        assertEquals(ids, LearnTopic.entries.map { it.id })
        for (topic in LearnTopic.entries) {
            assertEquals(topic, LearnTopic.of(topic.id))
        }
        assertNull(LearnTopic.of("not_a_topic"))
    }

    @Test
    fun `every topic has three to five paragraphs and at least two steps`() {
        val arrays =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(strings)
                .getElementsByTagName("string-array")
        val items =
            (0 until arrays.length)
                .map { arrays.item(it) as Element }
                .associate { it.getAttribute("name") to it.getElementsByTagName("item").length }
        for (topic in LearnTopic.entries) {
            assertTrue(items.getValue("learn_${topic.id}_body") in 3..5, topic.id)
            assertTrue(items.getValue("learn_${topic.id}_steps") >= 2, topic.id)
        }
    }
}
