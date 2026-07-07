package org.milkdev.dreamplayer.library

import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.TrackEntity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenreDaoIntegrationTest : BaseDatabaseTest() {

    private val now = 1_000_000L

    private val testArtist = ArtistEntity(name = "Test Artist")
    private val testAlbum = AlbumEntity(
        title = "Test Album",
        artistId = 1L,
        titleSortKey = "test album",
        artistSortKey = "test artist",
        lastSeenTimestamp = now,
    )
    private val testTrack = TrackEntity(
        filePath = "/music/test.mp3",
        title = "Test Track",
        artistName = "Test Artist",
        albumName = "Test Album",
        artistId = 1L,
        albumId = 1L,
        durationMs = 200_000L,
        fileSize = 5_000_000L,
        lastModified = now,
        lastSeenTimestamp = now,
    )

    private suspend fun insertArtistAndAlbum() {
        musicDao.insertArtists(listOf(testArtist))
        musicDao.insertAlbums(listOf(testAlbum))
    }

    // ── Track genre integration ───────────────────────────────────────

    @Test
    fun replaceTrackGenresWithResolvedParentsCreatesGenresInDb() = runTestInScope {
        insertArtistAndAlbum()
        musicDao.upsertTracks(listOf(testTrack))
        val trackId = musicDao.getTracksByPaths(listOf("/music/test.mp3")).first().id

        val rawGenres = listOf("shoegaze", "grunge")
        val parentGenres = rawGenres.flatMap {
            GenreDictionary.resolveParentGenres(it)
        }.distinct()

        assertContentEquals(listOf("Rock"), parentGenres)

        musicDao.replaceTrackGenres(
            trackId = trackId,
            genres = parentGenres.map { genre ->
                GenreEntity(
                    name = genre,
                    sortKey = genre.trim().lowercase(),
                    createdAt = now,
                )
            },
            sourceTrust = 60,
        )

        val genreIds = musicDao.getGenreIdMap(listOf("Rock"))
        assertEquals(1, genreIds.size, "Genre 'Rock' should exist in DB")
        assertEquals("Rock", genreIds.first().name)
    }

    @Test
    fun replaceTrackGenresDeduplicatesToSingleParent() = runTestInScope {
        insertArtistAndAlbum()
        musicDao.upsertTracks(listOf(testTrack))
        val trackId = musicDao.getTracksByPaths(listOf("/music/test.mp3")).first().id

        val rawGenres = listOf("shoegaze", "grunge", "dreamo")
        val parentGenres = rawGenres.flatMap {
            GenreDictionary.resolveParentGenres(it)
        }.distinct()

        assertContentEquals(listOf("Rock"), parentGenres)

        musicDao.replaceTrackGenres(
            trackId = trackId,
            genres = parentGenres.map { genre ->
                GenreEntity(
                    name = genre,
                    sortKey = genre.trim().lowercase(),
                    createdAt = now,
                )
            },
            sourceTrust = 60,
        )

        val genreIds = musicDao.getGenreIdMap(listOf("Rock"))
        assertEquals(1, genreIds.size, "All 3 raw genres should resolve to one parent 'Rock'")
        assertEquals("Rock", genreIds.first().name)
    }

    @Test
    fun replaceTrackGenresFiltersGarbageAndSkipsInsert() = runTestInScope {
        insertArtistAndAlbum()
        musicDao.upsertTracks(listOf(testTrack))

        val rawGenres = listOf("podcast", "audiobook")
        val parentGenres = rawGenres.flatMap {
            GenreDictionary.resolveParentGenres(it)
        }.distinct()
        assertTrue(parentGenres.isEmpty(), "Garbage genres should resolve to empty list")

        val noGenre = musicDao.getGenreIdMap(listOf("Rock"))
        assertTrue(noGenre.isEmpty(), "No genres should be in DB after garbage-only input")
    }

    // ── Album genre integration ───────────────────────────────────────

    @Test
    fun replaceAlbumGenresWithResolvedParentsCreatesGenresInDb() = runTestInScope {
        insertArtistAndAlbum()
        val album = musicDao.getAlbumIdMap().first()
        val albumId = album.id

        val rawGenres = listOf("shoegaze", "grunge")
        val parentGenres = rawGenres.flatMap {
            GenreDictionary.resolveParentGenres(it)
        }.distinct()
        assertContentEquals(listOf("Rock"), parentGenres)

        musicDao.replaceAlbumGenres(
            albumId = albumId,
            genres = parentGenres.map { genre ->
                GenreEntity(
                    name = genre,
                    sortKey = genre.trim().lowercase(),
                    createdAt = now,
                )
            },
            sourceTrust = 60,
        )

        val genreIds = musicDao.getGenreIdMap(listOf("Rock"))
        assertEquals(1, genreIds.size)
        assertEquals("Rock", genreIds.first().name)
    }

    @Test
    fun replaceAlbumGenresSkipsWhenAllResolveEmpty() = runTestInScope {
        insertArtistAndAlbum()
        val album = musicDao.getAlbumIdMap().first()
        val albumId = album.id

        val parentGenres = GenreDictionary.resolveParentGenres("podcast, vodcast")
        assertTrue(parentGenres.isEmpty())

        musicDao.replaceAlbumGenres(
            albumId = albumId,
            genres = parentGenres.map { genre ->
                GenreEntity(
                    name = genre,
                    sortKey = genre.trim().lowercase(),
                    createdAt = now,
                )
            },
            sourceTrust = 60,
        )

        assertTrue(
            musicDao.getGenreIdMap(listOf("podcast", "Electronic")).isEmpty(),
            "No genre entities should have been created",
        )
    }

    @Test
    fun replaceTrackGenresWithRealWorldDirtyDataResolvesCorrectly() = runTestInScope {
        insertArtistAndAlbum()

        val dirtyInputs = mapOf(
            "Soundtrack / Game" to listOf("Film Score"),
            "Classical; Pop-Rock" to listOf("Classical", "Rock"),
            "Metalcore/Djent" to listOf("Djent", "Metalcore"),
            "Rap; Hip-Hop; Trap" to listOf("Hip Hop", "Trap"),
            "Audiosmuth 101 /////" to emptyList(),
            "   " to emptyList(),
        )

        dirtyInputs.entries.forEachIndexed { index, (input, expectedParents) ->
            val uniquePath = "/music/dirty_test_${index}.mp3"
            val track = testTrack.copy(filePath = uniquePath, title = "Dirty Track $index")
            musicDao.upsertTracks(listOf(track))

            val trackId = musicDao.getTracksByPaths(listOf(uniquePath)).first().id

            val parentGenres = GenreDictionary.resolveParentGenres(input)

            if (parentGenres.isNotEmpty()) {
                musicDao.replaceTrackGenres(
                    trackId = trackId,
                    genres = parentGenres.map { genre ->
                        GenreEntity(
                            name = genre,
                            sortKey = genre.trim().lowercase(),
                            createdAt = now,
                        )
                    },
                    sourceTrust = 60,
                )
            }

            val dbGenres = musicDao.getGenreIdMap(expectedParents).map { it.name }.sorted()
            assertContentEquals(expectedParents, dbGenres, "Failed on input: '$input'")
        }
    }
}
