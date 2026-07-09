@file:Suppress("SpellCheckingInspection")

package org.milkdev.dreamplayer.library

data class ParentGenre(
    val name: String,
    val roots: List<String>,
)

data class SubgenreExplicitMap(
    val subgenreName: String,
    val parentName: String,
)

object GenreDictionary {

    val parents: List<ParentGenre> = listOf(
        // Pop & Mainstream
        ParentGenre("Pop", listOf("pop")),
        ParentGenre("K-Pop", listOf("k-pop", "kpop", "k pop")),
        ParentGenre("J-Pop", listOf("j-pop", "jpop", "j pop")),

        // Rock & Derivatives
        ParentGenre("Rock", listOf("rock")),
        ParentGenre("Alternative", listOf("alternative", "alt")),
        ParentGenre("Indie", listOf("indie")),
        ParentGenre("Punk", listOf("punk")),
        ParentGenre("Post-Punk", listOf("post-punk", "postpunk")),
        ParentGenre("New Wave", listOf("new wave", "newwave")),
        ParentGenre("Progressive", listOf("progressive", "prog")),
        ParentGenre("Psychedelic", listOf("psychedelic")),
        ParentGenre("Post-Rock", listOf("post-rock", "postrock")),
        ParentGenre("Math Rock", listOf("math rock", "mathrock")),
        ParentGenre("Stoner Rock", listOf("stoner rock", "stonerrock")),
        ParentGenre("Blues Rock", listOf("blues rock")),
        ParentGenre("Folk Rock", listOf("folk rock")),
        ParentGenre("Southern Rock", listOf("southern rock")),
        ParentGenre("Surf Rock", listOf("surf rock", "surf")),
        ParentGenre("Garage Rock", listOf("garage rock")),

        // Metal
        ParentGenre("Metal", listOf("metal")),
        ParentGenre("Thrash", listOf("thrash")),
        ParentGenre("Death Metal", listOf("death metal", "deathmetal")),
        ParentGenre("Black Metal", listOf("black metal", "blackmetal")),
        ParentGenre("Doom Metal", listOf("doom metal", "doommetal")),
        ParentGenre("Power Metal", listOf("power metal")),
        ParentGenre("Folk Metal", listOf("folk metal")),
        ParentGenre("Nu Metal", listOf("nu metal", "numetal")),

        // Hardcore & Punk Derivatives
        ParentGenre("Hardcore", listOf("hardcore")),
        ParentGenre("Metalcore", listOf("metalcore", "metal-core")),
        ParentGenre("Deathcore", listOf("deathcore")),
        ParentGenre("Djent", listOf("djent")),
        ParentGenre("Grindcore", listOf("grindcore")),
        ParentGenre("Post-Hardcore", listOf("post-hardcore", "posthardcore")),
        ParentGenre("Emo", listOf("emo")),
        ParentGenre("Pop Punk", listOf("pop punk", "poppunk")),
        ParentGenre("Screamo", listOf("screamo")),
        ParentGenre("Skate Punk", listOf("skate punk")),
        ParentGenre("Ska Punk", listOf("ska punk")),

        // Electronic
        ParentGenre("Electronic", listOf("electronic", "electronica", "edm")),
        ParentGenre("Techno", listOf("techno")),
        ParentGenre("House", listOf("house")),
        ParentGenre("Trance", listOf("trance")),
        ParentGenre("Dubstep", listOf("dubstep", "dub-step")),
        ParentGenre("Drum and Bass", listOf("drum and bass", "drum & bass", "drumandbass", "dnb")),
        ParentGenre("Ambient", listOf("ambient")),
        ParentGenre("IDM", listOf("idm")),
        ParentGenre("Downtempo", listOf("downtempo", "down-tempo", "down tempo")),
        ParentGenre("Trip-Hop", listOf("trip-hop", "trip hop", "triphop")),
        ParentGenre("Chillout", listOf("chillout", "chill-out", "chill out")),
        ParentGenre("Lo-fi", listOf("lo-fi", "lofi", "lo fi")),
        ParentGenre("Industrial", listOf("industrial")),
        ParentGenre("Electro", listOf("electro")),
        ParentGenre("Synthwave", listOf("synthwave", "synth-wave", "synth wave")),
        ParentGenre("Vaporwave", listOf("vaporwave", "vapourwave")),
        ParentGenre("Breakbeat", listOf("breakbeat", "break-beat", "break beat")),
        ParentGenre("Jungle", listOf("jungle")),
        ParentGenre("UK Garage", listOf("uk garage", "ukg")),
        ParentGenre("Grime", listOf("grime")),
        ParentGenre("Footwork", listOf("footwork")),
        ParentGenre("Hardstyle", listOf("hardstyle")),
        ParentGenre("Gabber", listOf("gabber")),

        // Hip Hop & R&B
        ParentGenre("Hip Hop", listOf("hip hop", "hip-hop", "hiphop", "rap")),
        ParentGenre("R&B", listOf("r&b", "rnb", "rhythm and blues", "randb")),
        ParentGenre("Soul", listOf("soul")),
        ParentGenre("Funk", listOf("funk")),
        ParentGenre("Trap", listOf("trap")),
        ParentGenre("Drill", listOf("drill")),

        // Jazz & Blues
        ParentGenre("Jazz", listOf("jazz")),
        ParentGenre("Blues", listOf("blues")),
        ParentGenre("Swing", listOf("swing")),
        ParentGenre("Big Band", listOf("big band")),
        ParentGenre("Bebop", listOf("bebop")),
        ParentGenre("Fusion", listOf("fusion")),
        ParentGenre("Smooth Jazz", listOf("smooth jazz")),

        // Classical & Orchestral
        ParentGenre("Classical", listOf("classical", "classic")),
        ParentGenre("Orchestral", listOf("orchestral", "orchestra")),
        ParentGenre("Chamber", listOf("chamber")),
        ParentGenre("Baroque", listOf("baroque")),
        ParentGenre("Opera", listOf("opera")),
        ParentGenre("Choral", listOf("choral", "choir", "chorus")),
        ParentGenre("Film Score", listOf("film score", "soundtrack", "score")),
        ParentGenre("Contemporary Classical", listOf("contemporary classical")),

        // Country & Folk
        ParentGenre("Country", listOf("country")),
        ParentGenre("Folk", listOf("folk")),
        ParentGenre("Bluegrass", listOf("bluegrass", "blue grass")),
        ParentGenre("Americana", listOf("americana")),
        ParentGenre("Outlaw Country", listOf("outlaw country")),
        ParentGenre("Country Rock", listOf("country rock")),
        ParentGenre("Alt Country", listOf("alt country", "alternative country")),

        // World & Regional
        ParentGenre("World", listOf("world")),
        ParentGenre("Latin", listOf("latin")),
        ParentGenre("Salsa", listOf("salsa")),
        ParentGenre("Reggae", listOf("reggae")),
        ParentGenre("Dancehall", listOf("dancehall")),
        ParentGenre("Ska", listOf("ska")),
        ParentGenre("Bossa Nova", listOf("bossa nova", "bossanova")),
        ParentGenre("Flamenco", listOf("flamenco")),
        ParentGenre("Samba", listOf("samba")),
        ParentGenre("Tango", listOf("tango")),
        ParentGenre("Afrobeats", listOf("afrobeats", "afrobeat")),
        ParentGenre("Reggaeton", listOf("reggaeton")),
        ParentGenre("Celtic", listOf("celtic")),
        ParentGenre("Cajun", listOf("cajun")),
        ParentGenre("Zydeco", listOf("zydeco")),
        ParentGenre("Calypso", listOf("calypso")),
        ParentGenre("Soca", listOf("soca")),

        // R&B Adjacent
        ParentGenre("Neo-Soul", listOf("neo-soul", "neosoul", "neo soul")),
        ParentGenre("Disco", listOf("disco")),
        ParentGenre("Nu-Disco", listOf("nu-disco", "nudisco")),
        ParentGenre("Boogie", listOf("boogie")),

        // Vocal & Performance
        ParentGenre("Vocal", listOf("vocal")),
        ParentGenre("A Cappella", listOf("a cappella", "acappella")),
        ParentGenre("Barbershop", listOf("barbershop")),
        ParentGenre("Doo-Wop", listOf("doo-wop", "doowop")),
        ParentGenre("Show Tunes", listOf("show tunes", "musical", "broadway")),
        ParentGenre("Cabaret", listOf("cabaret")),

        // Religious & Spiritual
        ParentGenre("Gospel", listOf("gospel")),
        ParentGenre("Christian", listOf("christian", "ccm")),
        ParentGenre("Spiritual", listOf("spiritual")),
        ParentGenre("Hymnal", listOf("hymnal", "hymn")),
        ParentGenre("Devotional", listOf("devotional")),

        // Holiday & Novelty
        ParentGenre("Holiday", listOf("holiday", "christmas")),
        ParentGenre("Children's", listOf("children", "kids")),
        ParentGenre("Comedy", listOf("comedy", "humor", "humour")),
        ParentGenre("Spoken Word", listOf("spoken word", "speech", "story")),

        // Instrumental & Misc
        ParentGenre("Instrumental", listOf("instrumental")),
        ParentGenre("Acoustic", listOf("acoustic", "unplugged")),
        ParentGenre("Easy Listening", listOf("easy listening", "easy-listening")),
        ParentGenre("New Age", listOf("new age", "newage")),
        ParentGenre("Meditation", listOf("meditation")),

        // Experimental & Avant-Garde
        ParentGenre("Experimental", listOf("experimental", "avant-garde", "avantgarde", "avant garde")),
        ParentGenre("Noise", listOf("noise")),
        ParentGenre("Drone", listOf("drone")),
        ParentGenre("Free Jazz", listOf("free jazz")),
        ParentGenre("Musique Concrète", listOf("musique concrète", "musique concrete")),
        ParentGenre("Minimalism", listOf("minimalism", "minimalist", "minimal")),
    )

    val explicitMappings: List<SubgenreExplicitMap> = listOf(
        // Pop — only true overrides
        SubgenreExplicitMap("bubblegum", "Pop"),

        // Rock — only entries without "rock" in the name
        SubgenreExplicitMap("shoegaze", "Rock"),
        SubgenreExplicitMap("shoegazing", "Rock"),
        SubgenreExplicitMap("dreamo", "Rock"),
        SubgenreExplicitMap("grunge", "Rock"),
        SubgenreExplicitMap("jangle pop", "Rock"),
        SubgenreExplicitMap("jangle-pop", "Rock"),
        SubgenreExplicitMap("paisley underground", "Rock"),
        SubgenreExplicitMap("neo-psychedelia", "Rock"),
        SubgenreExplicitMap("neo psychedelia", "Rock"),

        // Electronic — no subgenre name contains "electronic", "electronica", or "edm"
        SubgenreExplicitMap("microhouse", "Electronic"),
        SubgenreExplicitMap("micro-house", "Electronic"),
        SubgenreExplicitMap("minimal techno", "Electronic"),
        SubgenreExplicitMap("deep house", "Electronic"),
        SubgenreExplicitMap("progressive house", "Electronic"),
        SubgenreExplicitMap("tech house", "Electronic"),
        SubgenreExplicitMap("tech-house", "Electronic"),
        SubgenreExplicitMap("tribal house", "Electronic"),
        SubgenreExplicitMap("acid house", "Electronic"),
        SubgenreExplicitMap("acid techno", "Electronic"),
        SubgenreExplicitMap("acid", "Electronic"),
        SubgenreExplicitMap("detroit techno", "Electronic"),
        SubgenreExplicitMap("hard techno", "Electronic"),
        SubgenreExplicitMap("schranz", "Electronic"),
        SubgenreExplicitMap("psytrance", "Electronic"),
        SubgenreExplicitMap("psy-trance", "Electronic"),
        SubgenreExplicitMap("goa trance", "Electronic"),
        SubgenreExplicitMap("progressive trance", "Electronic"),
        SubgenreExplicitMap("uplifting trance", "Electronic"),
        SubgenreExplicitMap("vocal trance", "Electronic"),
        SubgenreExplicitMap("neurofunk", "Electronic"),
        SubgenreExplicitMap("liquid drum and bass", "Electronic"),
        SubgenreExplicitMap("liquid dnb", "Electronic"),
        SubgenreExplicitMap("liquid funk", "Electronic"),
        SubgenreExplicitMap("darkstep", "Electronic"),
        SubgenreExplicitMap("techstep", "Electronic"),
        SubgenreExplicitMap("ambient techno", "Electronic"),
        SubgenreExplicitMap("ambient house", "Electronic"),
        SubgenreExplicitMap("dark ambient", "Electronic"),
        SubgenreExplicitMap("drone ambient", "Electronic"),
        SubgenreExplicitMap("isolationism", "Electronic"),
        SubgenreExplicitMap("glitch", "Electronic"),
        SubgenreExplicitMap("glitch hop", "Electronic"),
        SubgenreExplicitMap("wonky", "Electronic"),
        SubgenreExplicitMap("chiptune", "Electronic"),
        SubgenreExplicitMap("bitpop", "Electronic"),
        SubgenreExplicitMap("8-bit", "Electronic"),
        SubgenreExplicitMap("breakcore", "Electronic"),
        SubgenreExplicitMap("speedcore", "Electronic"),
        SubgenreExplicitMap("terrorcore", "Electronic"),
        SubgenreExplicitMap("future bass", "Electronic"),
        SubgenreExplicitMap("future garage", "Electronic"),
        SubgenreExplicitMap("bass music", "Electronic"),
        SubgenreExplicitMap("bassline", "Electronic"),
        SubgenreExplicitMap("kraftwerk", "Electronic"),
        SubgenreExplicitMap("electroclash", "Electronic"),
        SubgenreExplicitMap("big beat", "Electronic"),
        SubgenreExplicitMap("bigbeat", "Electronic"),
        SubgenreExplicitMap("nu jazz", "Electronic"),
        SubgenreExplicitMap("nu-jazz", "Electronic"),
        SubgenreExplicitMap("future jazz", "Electronic"),

        // Hip Hop — only entries without "hip hop"/"rap" in the name
        SubgenreExplicitMap("gangsta", "Hip Hop"),
        SubgenreExplicitMap("boom bap", "Hip Hop"),
        SubgenreExplicitMap("east coast", "Hip Hop"),
        SubgenreExplicitMap("west coast", "Hip Hop"),
        SubgenreExplicitMap("dirty south", "Hip Hop"),
        SubgenreExplicitMap("crunk", "Hip Hop"),
        SubgenreExplicitMap("snap", "Hip Hop"),
        SubgenreExplicitMap("uk drill", "Hip Hop"),
        SubgenreExplicitMap("phonk", "Hip Hop"),
        SubgenreExplicitMap("drift phonk", "Hip Hop"),
        SubgenreExplicitMap("horrorcore", "Hip Hop"),

        // Metal — only entries without "metal" in the name
        SubgenreExplicitMap("gothic doom", "Metal"),
        SubgenreExplicitMap("sludge", "Metal"),
        SubgenreExplicitMap("stoner doom", "Metal"),
        SubgenreExplicitMap("funeral doom", "Metal"),
        SubgenreExplicitMap("drone doom", "Metal"),
        SubgenreExplicitMap("dsbm", "Metal"),
        SubgenreExplicitMap("melodeath", "Metal"),
        SubgenreExplicitMap("slam", "Metal"),

        // Jazz — only entries without "jazz" in the name
        SubgenreExplicitMap("hard bop", "Jazz"),
        SubgenreExplicitMap("post-bop", "Jazz"),
        SubgenreExplicitMap("third stream", "Jazz"),
        SubgenreExplicitMap("manouche", "Jazz"),
        SubgenreExplicitMap("ecm", "Jazz"),

        // Country — only entries without "country" in the name
        SubgenreExplicitMap("nashville sound", "Country"),
        SubgenreExplicitMap("honky tonk", "Country"),
        SubgenreExplicitMap("honky-tonk", "Country"),
        SubgenreExplicitMap("bakersfield sound", "Country"),
        SubgenreExplicitMap("red dirt", "Country"),

        // Punk — only entries without "punk" in the name
        SubgenreExplicitMap("oi", "Punk"),
        SubgenreExplicitMap("crust", "Punk"),
        SubgenreExplicitMap("d-beat", "Punk"),
        SubgenreExplicitMap("dbeat", "Punk"),
        SubgenreExplicitMap("crossover thrash", "Punk"),
        SubgenreExplicitMap("digital hardcore", "Punk"),
        SubgenreExplicitMap("psychobilly", "Punk"),

        // Classical — only entries without "classical"/"classic" in the name
        SubgenreExplicitMap("early music", "Classical"),
        SubgenreExplicitMap("renaissance", "Classical"),
        SubgenreExplicitMap("medieval", "Classical"),
        SubgenreExplicitMap("romantic", "Classical"),
        SubgenreExplicitMap("romantic period", "Classical"),
        SubgenreExplicitMap("impressionist", "Classical"),
        SubgenreExplicitMap("impressionism", "Classical"),
        SubgenreExplicitMap("serialism", "Classical"),
        SubgenreExplicitMap("twelve-tone", "Classical"),
        SubgenreExplicitMap("electroacoustic", "Classical"),
        SubgenreExplicitMap("art song", "Classical"),
        SubgenreExplicitMap("lieder", "Classical"),

        // Reggae — only entries without "reggae" in the name
        SubgenreExplicitMap("dub", "Reggae"),
        SubgenreExplicitMap("rocksteady", "Reggae"),
        SubgenreExplicitMap("lovers rock", "Reggae"),
        SubgenreExplicitMap("ragga", "Reggae"),

        // Folk — only entries without "folk" in the name
        SubgenreExplicitMap("new weird america", "Folk"),
        SubgenreExplicitMap("singer-songwriter", "Folk"),

        // Soul — only entries without "soul" in the name
        SubgenreExplicitMap("motown", "Soul"),

        // World — only entries without "world" in the name
        SubgenreExplicitMap("global", "World"),
        SubgenreExplicitMap("ethnic", "World"),
        SubgenreExplicitMap("traditional", "World"),

        // Latin — only entries without "latin" in the name
        SubgenreExplicitMap("bachata", "Latin"),
        SubgenreExplicitMap("merengue", "Latin"),
        SubgenreExplicitMap("cumbia", "Latin"),
        SubgenreExplicitMap("ranchera", "Latin"),
        SubgenreExplicitMap("mariachi", "Latin"),
        SubgenreExplicitMap("norteño", "Latin"),
        SubgenreExplicitMap("norteno", "Latin"),
        SubgenreExplicitMap("banda", "Latin"),
        SubgenreExplicitMap("corrido", "Latin"),
        SubgenreExplicitMap("regional mexican", "Latin"),

        // R&B — only entries without "r&b"/"rnb" in the name
        SubgenreExplicitMap("pbjr", "R&B"),
        SubgenreExplicitMap("new jack swing", "R&B"),
        SubgenreExplicitMap("quiet storm", "R&B"),
        SubgenreExplicitMap("slow jam", "R&B"),
        SubgenreExplicitMap("hip hop soul", "R&B"),
        SubgenreExplicitMap("neo soul", "R&B"),

        // Experimental — no name contains "experimental"/"avant-garde"
        SubgenreExplicitMap("free improvisation", "Experimental"),
        SubgenreExplicitMap("free improv", "Experimental"),
        SubgenreExplicitMap("eai", "Experimental"),
        SubgenreExplicitMap("lowercase", "Experimental"),
        SubgenreExplicitMap("onkyo", "Experimental"),
        SubgenreExplicitMap("sound art", "Experimental"),
        SubgenreExplicitMap("sound collage", "Experimental"),
        SubgenreExplicitMap("plunderphonics", "Experimental"),
        SubgenreExplicitMap("musique concrète", "Experimental"),
        SubgenreExplicitMap("musique concrete", "Experimental"),
        SubgenreExplicitMap("turntablism", "Experimental"),
    )

    val explicitSubgenreToParentName: Map<String, String> by lazy {
        explicitMappings.associate { it.subgenreName.lowercase() to it.parentName }
    }

    val sortedRootEntries: List<Pair<String, String>> by lazy {
        parents.flatMap { parent ->
            parent.roots.map { root -> root.lowercase() to parent.name }
        }.distinctBy { it.first }
            .sortedByDescending { it.first.length }
    }

    fun tokenizeAndClean(rawGenre: String?): List<String> {
        if (rawGenre.isNullOrBlank()) return emptyList()
        return rawGenre.split(',', ';', '/', '\\')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun resolveParentGenres(rawGenre: String?): List<String> {
        val tokens = tokenizeAndClean(rawGenre)
        if (tokens.isEmpty()) return emptyList()

        return tokens.mapNotNull { token ->
            explicitSubgenreToParentName[token]
                ?: sortedRootEntries.firstOrNull { (root, _) -> token.contains(root) }
                    ?.second
        }.distinct().sorted()
    }
}
