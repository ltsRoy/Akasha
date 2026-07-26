package com.MeshLink.android.model

import org.junit.Assert.*
import org.junit.Test

class MeshLinkMessageTest {

    @Test
    fun testParseLocation_GeoUri() {
        val geo = "geo:19.0760,72.8777"
        val coords = parseLocation(geo)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_GeoUriWithQuery() {
        val geo = "geo:19.0760,72.8777?q=19.0760,72.8777"
        val coords = parseLocation(geo)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_CommaSeparated() {
        val csv = " 19.0760,  72.8777 "
        val coords = parseLocation(csv)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_JsonFormat() {
        val json = "{\"latitude\": 19.0760, \"longitude\": 72.8777}"
        val coords = parseLocation(json)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_JsonFormatShortKeys() {
        val json = "{\"lat\": 19.0760, \"lng\": 72.8777}"
        val coords = parseLocation(json)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_TextLines() {
        val text = "📍 Shared Location\nLat: 19.0760\nLng: 72.8777"
        val coords = parseLocation(text)
        assertNotNull(coords)
        assertEquals(19.0760, coords!!.latitude, 0.0001)
        assertEquals(72.8777, coords.longitude, 0.0001)
    }

    @Test
    fun testParseLocation_Invalid() {
        assertNull(parseLocation("Hello World"))
        assertNull(parseLocation("19.0760"))
        assertNull(parseLocation("geo:abc,def"))
    }
}
