package com.casadei.aavideoscreensaver.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpvAndPitsportParsingTest {

    private val samplePpvStreamsJson = """
        {
          "success": true,
          "timestamp": 1794404920,
          "streams": [
            {
              "category": "American Football",
              "id": 26,
              "always_live": false,
              "streams": [
                {
                  "id": 18172,
                  "name": "NFL Network",
                  "tag": "24/7 channel",
                  "source_tag": "HD | NFL Network (ESPN)",
                  "uri_name": "nfl-network",
                  "starts_at": 0,
                  "ends_at": 0,
                  "always_live": 1,
                  "category_name": "American Football",
                  "iframe": "https://taifood-blog.asia/embed/nfl-network",
                  "substreams": [
                    {
                      "id": 1018172,
                      "name": "NFL Network",
                      "source_tag": "SD | NFL Network (ESPN)",
                      "iframe": "https://taifood-blog.asia/embed/nfl-network_540p"
                    }
                  ],
                  "viewers": "22"
                },
                {
                  "id": 31952,
                  "name": "Washington Commanders at New York Giants",
                  "tag": "NFL",
                  "source_tag": "HD | Home",
                  "starts_at": 1794532500,
                  "ends_at": 1794546900,
                  "always_live": 0,
                  "category_name": "American Football",
                  "iframe": "https://taifood-blog.asia/embed/nfl/2026-11-12/wsh-nyg",
                  "substreams": [],
                  "viewers": "0"
                }
              ]
            },
            {
              "category": "Basketball",
              "id": 37,
              "always_live": false,
              "streams": [
                {
                  "id": 32029,
                  "name": "Washington Wizards vs. Indiana Pacers",
                  "tag": "NBA",
                  "source_tag": "HD | Home",
                  "starts_at": 1794441600,
                  "ends_at": 1794452400,
                  "always_live": 0,
                  "category_name": "Basketball",
                  "iframe": "https://taifood-blog.asia/embed/nba/2026-11-11/wsh-ind",
                  "substreams": [
                    {
                      "id": 32030,
                      "name": "Washington Wizards vs. Indiana Pacers",
                      "source_tag": "HD | Away",
                      "iframe": "https://taifood-blog.asia/embed/nba/2026-11-11/wsh-ind/away"
                    }
                  ],
                  "viewers": "5"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private val samplePpvVodsJson = """
        {
          "success": true,
          "timestamp": 1794404920,
          "vods": [
            {
              "category": "Arm Wrestling",
              "id": 24,
              "vods": [
                {
                  "id": 76,
                  "name": "East vs West 23: Germany",
                  "tag": "ppv.to",
                  "uri_name": "east-vs-west-23",
                  "event_date": 1776531600,
                  "views": 3127
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `PpvClient parses nested streams categories and live filter`() {
        val liveOnly = PpvClient.parseStreamsResponse(samplePpvStreamsJson, onlyLiveNow = true)
        assertEquals(1, liveOnly.size)
        val nfl = liveOnly[0]
        assertEquals("18172", nfl.id)
        assertEquals("NFL Network", nfl.name)
        assertTrue(nfl.alwaysLive)
        assertTrue(nfl.isLiveNow)
        assertEquals(22, nfl.viewers)
        assertEquals("HD | NFL Network (ESPN)", nfl.sourceTag)
        assertEquals(1, nfl.substreams.size)
        assertEquals("SD | NFL Network (ESPN)", nfl.substreams[0].name)

        val allStreams = PpvClient.parseStreamsResponse(samplePpvStreamsJson, onlyLiveNow = false)
        assertEquals(3, allStreams.size)

        val basketballOnly = PpvClient.parseStreamsResponse(samplePpvStreamsJson, onlyLiveNow = false, categoryFilter = "37")
        assertEquals(1, basketballOnly.size)
        assertEquals("Washington Wizards vs. Indiana Pacers", basketballOnly[0].name)

        val categories = PpvClient.parseCategoriesResponse(samplePpvStreamsJson)
        assertEquals(2, categories.size)
        assertEquals("26", categories[0].id)
        assertTrue(categories[0].name.contains("Football Americano"))
    }

    @Test
    fun `PpvClient parses VODs and play URL responses`() {
        val vods = PpvClient.parseVodsResponse(samplePpvVodsJson)
        assertEquals(1, vods.size)
        assertEquals("76", vods[0].id)
        assertEquals("East vs West 23: Germany", vods[0].name)
        assertTrue(vods[0].isVod)
        assertEquals("https://api.ppv.st/api/vods/76", vods[0].embedUrl)

        val vodDetailJson = """
            {"success":true,"data":{"id":76,"name":"East vs West 23: Germany","source":{"type":"playlist","data":"https://vod.television.st/evw23/index.m3u8"}}}
        """.trimIndent()
        assertEquals("https://vod.television.st/evw23/index.m3u8", PpvClient.parsePlayUrlFromJson(vodDetailJson))

        val streamPlayJson = """
            {"success":true,"data":{"id":18172,"sources":[{"type":"iframe","data":"https://taifood-blog.asia/embed/nfl-network"}]}}
        """.trimIndent()
        assertEquals("https://taifood-blog.asia/embed/nfl-network", PpvClient.parsePlayUrlFromJson(streamPlayJson))
    }

    @Test
    fun `PitsportClient parses live-now upcoming and program play response`() {
        val liveNowJson = """
            {
              "ok": true,
              "data": {
                "live": [],
                "upcoming": [
                  {
                    "programId": 102871,
                    "slug": "washington-wizards-at-indiana-pacers",
                    "title": [{"text": "Washington Wizards", "flag": null}, {"text": "Indiana Pacers", "flag": null}],
                    "titleText": "Washington Wizards at Indiana Pacers",
                    "footer": "NBA",
                    "sessionStart": 1794441600,
                    "href": "/programs/102871",
                    "playHref": "/programs/102871/play",
                    "liveState": "upcoming",
                    "hasVideo": false
                  }
                ]
              }
            }
        """.trimIndent()

        val programs = PitsportClient.parseProgramsResponse(liveNowJson, includeUpcoming = true)
        assertEquals(1, programs.size)
        val p = programs[0]
        assertEquals("102871", p.id)
        assertEquals("Washington Wizards at Indiana Pacers", p.title)
        assertEquals("NBA", p.category)
        assertEquals("🏀", p.categoryEmoji)
        assertFalse(p.live)
        assertEquals(1794441600L, p.startsAt)

        val playResponseJson = """
            {
              "ok": true,
              "data": {
                "program": {
                  "programId": 102871,
                  "titleText": "Washington Wizards at Indiana Pacers",
                  "cardFooter": "NBA",
                  "liveState": "live",
                  "sessionStart": 1794441600
                },
                "video": {
                  "embedId": 10,
                  "label": "Main HD",
                  "embedUrl": "https://embdlol.st/embed/nba-1",
                  "allowed": true
                },
                "videos": [
                  {
                    "embedId": 10,
                    "label": "Main HD",
                    "embedUrl": "https://embdlol.st/embed/nba-1",
                    "allowed": true
                  },
                  {
                    "embedId": 11,
                    "label": "Backup",
                    "embedUrl": "https://embdlol.st/embed/nba-2",
                    "allowed": true
                  }
                ],
                "hasVideo": true
              }
            }
        """.trimIndent()

        val detail = PitsportClient.parseProgramDetailResponse(playResponseJson)
        assertNotNull(detail)
        assertTrue(detail!!.live)
        assertEquals(2, detail.streams.size)
        assertEquals("Main HD", detail.streams[0].name)
        assertEquals("https://embdlol.st/embed/nba-1", detail.streams[0].embedUrl)
    }
}
