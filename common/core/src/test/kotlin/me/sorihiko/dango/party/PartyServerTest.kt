package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PartyServerTest {
    private val host = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val replies = mutableListOf<JsonObject>()
    private var previousLink: PartyNet.Link? = null
    private fun json(value: String) = JsonParser().parse(value)
    private fun request(player: UUID, op: String, path: String = "", value: JsonElement? = null): JsonObject {
        replies.clear()
        PartyServer.handle(player, JsonObject().apply {
            addProperty("id", 1); addProperty("op", op); addProperty("path", path)
            if (value != null) add("value", value)
        })
        return replies.single { it.has("re") }
    }
    private fun status(player: UUID, op: String, path: String, value: String) =
        request(player, op, path, json(value)).get("status").asInt

    @Before fun setup() {
        PartyServer.clear()
        previousLink = PartyNet.link
        PartyNet.link = object : PartyNet.Link {
            override fun toServer(frame: String) = false
            override fun toPlayer(player: UUID, frame: String) {
                replies += json(frame.split('|', limit = 4)[3]).asJsonObject
            }
        }
        assertEquals(200, status(host, "put", "rooms/D-TEST", """{"host":"$host","memberControl":true,"members":{"$host":{"lastSeen":${System.currentTimeMillis()}}}}"""))
        assertEquals(200, status(member, "put", "rooms/D-TEST/members/$member", """{"name":"Member","lastSeen":${System.currentTimeMillis()}}"""))
    }
    @After fun cleanup() { PartyServer.clear(); PartyNet.link = previousLink }

    @Test fun identityAndControlsAreEnforcedByServerConnection() {
        assertEquals(host.toString(), request(host, "hello").get("uid").asString)
        assertEquals(403, status(member, "put", "rooms/D-TEST/host", "\"$member\""))
        assertEquals(403, status(member, "put", "rooms/D-TEST/members/$host/name", "\"Spoof\""))
        assertEquals(403, status(outsider, "put", "rooms/D-TEST/req", """{"op":"next"}"""))
        assertEquals(200, status(member, "put", "rooms/D-TEST/req", """{"op":"next"}"""))
        assertEquals(200, status(host, "patch", "rooms/D-TEST", """{"memberControl":false}"""))
        assertEquals(403, status(member, "put", "rooms/D-TEST/req", """{"op":"pause"}"""))
        assertEquals(403, status(member, "patch", "rooms/D-TEST", """{"members/$member/name":"Spoof","host":"$member"}"""))
        assertEquals(host.toString(), request(host, "get", "rooms/D-TEST/host").get("value").asString)
    }

    @Test fun disconnectRemovesMemberAndClosesHostsRoom() {
        PartyServer.playerLeft(member)
        assertTrue(request(host, "get", "rooms/D-TEST/members/$member").get("value").isJsonNull)
        assertEquals(403, status(member, "put", "rooms/D-TEST/req", """{"op":"next"}"""))
        PartyServer.playerLeft(host)
        assertTrue(request(outsider, "get", "rooms/D-TEST").get("value").isJsonNull)
    }
}
