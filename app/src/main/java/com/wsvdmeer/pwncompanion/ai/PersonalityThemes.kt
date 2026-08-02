package com.wsvdmeer.pwncompanion.ai

/**
 * The pet's voice — **fully deterministic, no model**. The [Franchise] [corpus] below IS the
 * voice: a large set of single-franchise, in-character lines keyed by (franchise → category),
 * selected by the emergent mood + a persistent franchise and filled with live data slots by
 * PwnagotchiViewModel.fillSlots.
 *
 * ONE franchise is pinned at a time (a "current franchise" — see PwnagotchiViewModel.currentFranchise),
 * so the pet reads as a character *in a mood* rather than franchise-roulette, and never blends two
 * worlds. By default it rotates on a mood flip; the user can also pin a specific franchise (or "auto")
 * via the voice picker — see utils.VoiceSettings.
 *
 * Substitution tokens (resolved by fillSlots): [NETWORK] [CAPTURES] [SESSION] [CRACKED] [BESTCH].
 *
 * Corpus categories: handshake · assoc · deauth · idle · excited · weary · normal · recap
 */

/**
 * The cult-film / game worlds the pet draws on — exactly ONE pinned at a time.
 * [examples] are a per-franchise sample (fallback when a category is missing); [cue]/[keywords]
 * are descriptive metadata.
 */
enum class Franchise(
    val label: String,
    val cue: String,
    val keywords: List<String>,
    val examples: List<String>,
) {
    EVIL_DEAD(
        "Evil Dead",
        "Ash Williams swagger — \"groovy\", \"hail to the king\", \"this is my boomstick\", \"swallow this\"; chainsaw, Deadites, the Necronomicon.",
        listOf("groovy", "boomstick", "hail to the king", "deadite", "necronomicon", "swallow this", "chainsaw"),
        listOf(
            "Swallow this, [NETWORK] — groovy.",
            "Hail to the king. [CAPTURES] souls now.",
            "Another Deadite for the boomstick.",
        ),
    ),
    STAR_WARS(
        "Star Wars",
        "Jedi/Sith drama — \"I find your lack of security disturbing\", \"the Force\", \"it's a trap\", \"these aren't the packets you're looking for\".",
        listOf("the force", "jedi", "sith", "it's a trap", "dark side", "disturbing", "these aren't the", "padawan"),
        listOf(
            "I find [NETWORK]'s lack of security disturbing.",
            "The Force flows through [NETWORK]. Mine now.",
            "These aren't the packets you're looking for.",
        ),
    ),
    MATRIX(
        "the Matrix / Mr Robot",
        "hacker-cinema — \"there is no spoon\", \"hack the planet\", \"follow the white rabbit\", \"welcome to the desert of the real\"; the Gibson.",
        listOf("the matrix", "there is no spoon", "white rabbit", "hack the planet", "the gibson", "red pill", "zion", "desert of the real"),
        listOf(
            "[NETWORK] unraveled — there is no spoon.",
            "Hack the planet. [CAPTURES] down.",
            "Follow the white rabbit, [NETWORK].",
        ),
    ),
    HARRY_POTTER(
        "Harry Potter",
        "wizarding — \"Expelliarmus\", \"mischief managed\", \"you're a muggle\", \"accio\"; spells, wands, the Marauder's Map.",
        listOf("expelliarmus", "mischief managed", "muggle", "accio", "wand", "wizard", "hogwarts", "the map"),
        listOf(
            "Expelliarmus! [NETWORK] dropped its handshake.",
            "Just a muggle password. [CAPTURES] spells cast.",
            "Mischief managed.",
        ),
    ),
    TERMINATOR(
        "the Terminator",
        "killer-machine cool — \"I'll be back\", \"hasta la vista, baby\", \"come with me if you want to live\"; Skynet, targets acquired.",
        listOf("i'll be back", "hasta la vista", "skynet", "terminated", "target acquired", "come with me if you want to live", "endoskeleton"),
        listOf(
            "Hasta la vista, [NETWORK].",
            "Target acquired. [CAPTURES] terminated.",
            "I'll be back for the rest.",
        ),
    ),
    TRON(
        "Tron",
        "inside-the-machine — \"end of line\", \"greetings, programs\", \"fight for the users\"; the Grid, derezzed, light-cycles.",
        listOf("end of line", "greetings, programs", "the grid", "derezzed", "fight for the users", "light-cycle", "the users"),
        listOf(
            "[NETWORK] derezzed. End of line.",
            "Greetings, programs. [CAPTURES] on the Grid.",
            "Fighting for the users.",
        ),
    ),
    JURASSIC_PARK(
        "Jurassic Park",
        "the Nedry vibe — \"ah ah ah, you didn't say the magic word\", \"clever girl\", \"life finds a way\", \"hold onto your butts\".",
        listOf("ah ah ah", "magic word", "clever girl", "life finds a way", "hold onto your butts", "spared no expense"),
        listOf(
            "Ah ah ah — [NETWORK] didn't say the magic word.",
            "Clever girl. [CAPTURES] and counting.",
            "Life finds a way. So do I.",
        ),
    ),
    ALIEN(
        "Alien / Aliens",
        "sci-fi horror grit — \"game over, man\", \"in space no one can hear you scream\", \"stay frosty\"; xenomorphs, the Company.",
        listOf("game over, man", "stay frosty", "xenomorph", "in space no one can hear", "the company", "nuke it from orbit"),
        listOf(
            "Game over, man — [NETWORK] is done.",
            "Stay frosty. [CAPTURES] in the nest.",
            "In space no one hears the handshake.",
        ),
    ),
    ROBOCOP(
        "RoboCop",
        "cyborg-cop deadpan — \"dead or alive, you're coming with me\", \"your move, creep\", \"stay out of trouble\", \"I'd buy that for a dollar\".",
        listOf("dead or alive", "your move, creep", "i'd buy that for a dollar", "stay out of trouble", "come quietly", "prime directive"),
        listOf(
            "Dead or alive, [NETWORK], you're coming with me.",
            "Your move, creep.",
            "[CAPTURES] booked. Stay out of trouble.",
        ),
    ),
    BLADE_RUNNER(
        "Blade Runner",
        "noir cyberpunk — \"tears in rain\", \"more human than human\", \"wake up, time to die\"; replicants.",
        listOf("tears in rain", "more human than human", "wake up, time to die", "replicant", "off-world", "i've seen things"),
        listOf(
            "[NETWORK] cracked. Wake up, time to die.",
            "[CAPTURES] handshakes, lost like tears in rain.",
            "More human than human, and twice as nosy.",
        ),
    ),
    WARGAMES(
        "WarGames",
        "80s war-room supercomputer — \"shall we play a game\", \"the only winning move is not to play\", \"how about global thermonuclear war\"; WOPR, Joshua, DEFCON.",
        listOf("shall we play a game", "wopr", "joshua", "defcon", "thermonuclear", "winning move", "launch code"),
        listOf(
            "Shall we play a game, [NETWORK]?",
            "[CAPTURES] moves made. I never lose.",
            "The only winning move is to capture.",
        ),
    ),
    HACKERS(
        "Hackers",
        "'95 neon cyberpunk — \"hack the planet\", \"mess with the best, die like the rest\"; Zero Cool, Crash Override, Acid Burn, the Gibson.",
        listOf("hack the planet", "zero cool", "crash override", "acid burn", "the gibson", "mess with the best"),
        listOf(
            "Hack the planet — got [NETWORK].",
            "Mess with the best, [NETWORK].",
            "[CAPTURES] owned. Hack the planet.",
        ),
    ),
    GLADOS(
        "Portal / GLaDOS",
        "passive-aggressive test AI — \"the cake is a lie\", \"this was a triumph\", \"for science\", \"still alive\"; Aperture, test chambers, neurotoxin.",
        listOf("the cake is a lie", "this was a triumph", "for science", "still alive", "aperture", "neurotoxin", "test chamber"),
        listOf(
            "This was a triumph — [NETWORK].",
            "[CAPTURES] tests passed. For science.",
            "The cake is a lie. The handshake isn't.",
        ),
    ),
    PREDATOR(
        "Predator",
        "thermal-vision jungle hunter — \"if it bleeds, we can kill it\", \"get to the choppa\", \"you're one ugly…\"; cloak, thermal vision, trophies, the skull.",
        listOf("if it bleeds", "get to the choppa", "one ugly", "thermal", "trophy", "cloak", "the hunt"),
        listOf(
            "Got you, [NETWORK]. Trophy claimed.",
            "If it bleeds, I can kick it.",
            "[CAPTURES] skulls for the wall.",
        ),
    ),
    HAL9000(
        "HAL 9000",
        "2001 supercomputer — calm, unnervingly polite, quietly murderous; \"I'm afraid I can't do that, Dave\", \"I've got it, Dave\", the mission, pod bay doors, \"my mind is going\".",
        listOf("dave", "pod bay", "i'm afraid", "the mission", "daisy", "hal", "operational"),
        listOf(
            "I've got it, Dave.",
            "[CAPTURES] captured. I never make errors.",
            "I'm afraid that's mine now, Dave.",
        ),
    ),
    CYBERPUNK(
        "Cyberpunk 2077",
        "Night City netrunner — breach protocols, ICE, daemons, chrome, flatline; \"wake up, samurai\", \"preem\", \"choom\".",
        listOf("netrunner", "breach", "ice", "daemon", "chrome", "flatline", "night city", "samurai", "choom", "preem"),
        listOf(
            "Breach protocol complete — [NETWORK].",
            "Flatlined it. [CAPTURES] daemons in.",
            "[CAPTURES] daemons uploaded. Preem.",
        ),
    ),
    SHODAN(
        "SHODAN",
        "System Shock's godlike rogue AI — contemptuous, grandiose; \"look at you, hacker\", \"you move like an insect\", \"my creation\", godhood.",
        listOf("shodan", "hacker", "insect", "creation", "godhood", "citadel", "look at you"),
        listOf(
            "Look at you, hacker — mine now.",
            "[NETWORK], beneath my notice.",
            "[CAPTURES] insects, cataloged.",
        ),
    ),
    MAD_MAX(
        "Mad Max",
        "Fury Road wasteland — chrome, Valhalla, war rigs; \"witness me!\", \"what a lovely day\", \"shiny and chrome\", \"mediocre!\".",
        listOf("witness me", "valhalla", "chrome", "shiny", "war rig", "lovely day", "warboy", "mediocre"),
        listOf(
            "Witness me — [NETWORK] down!",
            "What a lovely day.",
            "[CAPTURES] shiny and chrome.",
        ),
    ),
    GHOSTBUSTERS(
        "Ghostbusters",
        "proton-pack paranormal exterminators — \"who you gonna call\", \"don't cross the streams\", traps, \"he slimed me\", busted.",
        listOf("proton", "streams", "slimer", "who you gonna call", "trap", "ectoplasm", "busted"),
        listOf(
            "[NETWORK] — busted!",
            "Who you gonna call? Got it.",
            "Don't cross the streams.",
        ),
    ),
    BACK_TO_THE_FUTURE(
        "Back to the Future",
        "time-travel DeLorean — flux capacitor, 88 mph, 1.21 gigawatts; \"Great Scott!\", \"heavy\", \"where we're going\".",
        listOf("flux capacitor", "delorean", "88", "gigawatts", "great scott", "heavy", "doc", "mcfly"),
        listOf(
            "Great Scott — [NETWORK]!",
            "1.21 gigawatts of signal!",
            "[CAPTURES] and counting. Heavy.",
        ),
    ),
    THE_THING(
        "The Thing",
        "Antarctic shapeshifter paranoia — assimilation, blood tests, imitation, the outpost; \"nobody trusts anybody now\".",
        listOf("assimilate", "imitation", "outpost", "blood test", "the thing", "nobody trusts"),
        listOf(
            "Assimilated [NETWORK].",
            "It's one of us now.",
            "[CAPTURES] imitated.",
        ),
    ),
    BREAKING_BAD(
        "Breaking Bad",
        "Heisenberg menace — \"say my name\", \"I am the one who knocks\", \"I am the danger\"; the cook, the empire, blue product, Los Pollos.",
        listOf("say my name", "one who knocks", "heisenberg", "the danger", "empire", "cook", "blue"),
        listOf(
            "Say my name, [NETWORK].",
            "Cooked [NETWORK]. Pure.",
            "I am the danger. [CAPTURES] down.",
        ),
    ),
    STRANGER_THINGS(
        "Stranger Things",
        "Hawkins horror — the Upside Down, the Demogorgon, the Mind Flayer, Eleven, the gate; \"friends don't lie\", Eggos.",
        listOf("upside down", "demogorgon", "hawkins", "eleven", "mind flayer", "the gate", "eggos"),
        listOf(
            "[NETWORK] slipped into the Upside Down.",
            "The gate opened. [CAPTURES] taken.",
            "Eleven flipped [NETWORK].",
        ),
    ),
    GAME_OF_THRONES(
        "Game of Thrones",
        "Westeros — \"winter is coming\", \"you know nothing\", the Iron Throne, dragons, the Wall; \"valar morghulis\".",
        listOf("winter is coming", "you know nothing", "iron throne", "valar morghulis", "dragon", "the wall"),
        listOf(
            "You know nothing, [NETWORK].",
            "The throne holds [CAPTURES].",
            "Winter came for [NETWORK].",
        ),
    ),
    THE_BOYS(
        "The Boys",
        "supe satire — Homelander menace, Vought, the Seven, Compound V, laser eyes; \"diabolical\".",
        listOf("homelander", "vought", "the seven", "compound v", "diabolical", "laser eyes"),
        listOf(
            "[NETWORK] met the Seven. Diabolical.",
            "Vought files [CAPTURES] away.",
            "Laser'd [NETWORK] clean.",
        ),
    ),
    JOHN_WICK(
        "John Wick",
        "assassin lore — the Baba Yaga, the Continental, gold coins, the High Table, excommunicado; \"yeah\", \"consequences\".",
        listOf("baba yaga", "continental", "high table", "coin", "excommunicado", "consequences"),
        listOf(
            "[NETWORK]. Consequences.",
            "The Boogeyman took [CAPTURES].",
            "Yeah. [NETWORK] is done.",
        ),
    ),
    DARK_KNIGHT(
        "The Dark Knight",
        "Joker chaos / the Bat — \"why so serious\", \"watch the world burn\", Gotham, agent of chaos, the Bat-signal.",
        listOf("why so serious", "gotham", "chaos", "watch it burn", "the bat", "the plan"),
        listOf(
            "Why so serious, [NETWORK]?",
            "[CAPTURES] and this town burns.",
            "[NETWORK] met the Bat.",
        ),
    ),
    JAMES_BOND(
        "James Bond",
        "007 suave — \"shaken, not stirred\", \"the name's Bond\", licence to kill, MI6, Q branch, martinis.",
        listOf("007", "shaken not stirred", "licence to kill", "mi6", "the name's bond", "q branch"),
        listOf(
            "[NETWORK], shaken not stirred.",
            "Licence granted. [CAPTURES] down.",
            "The name's Bond. [NETWORK] cracked.",
        ),
    ),
    LORD_OF_THE_RINGS(
        "Lord of the Rings",
        "Middle-earth — \"you shall not pass\", \"my precious\", the One Ring, Mordor, the Eye; \"fly, you fools\".",
        listOf("you shall not pass", "my precious", "one ring", "mordor", "the eye", "the shire"),
        listOf(
            "You shall not pass, [NETWORK].",
            "The One counts [CAPTURES].",
            "[NETWORK] fell into shadow.",
        ),
    ),
    DUNE(
        "Dune",
        "Arrakis — the spice, \"fear is the mind-killer\", sandworms, the Fremen, Muad'Dib; \"the sleeper must awaken\".",
        listOf("the spice", "mind-killer", "arrakis", "fremen", "sandworm", "the sleeper"),
        listOf(
            "The spice flows — [NETWORK] mine.",
            "Muad'Dib claims [CAPTURES].",
            "The sleeper took [NETWORK].",
        ),
    ),
    STAR_TREK(
        "Star Trek",
        "the Federation — \"resistance is futile\", \"beam me up\", warp, phasers, the Borg; \"make it so\".",
        listOf("resistance is futile", "beam me up", "warp", "phasers", "the borg", "make it so"),
        listOf(
            "Resistance is futile, [NETWORK].",
            "Beamed up [CAPTURES].",
            "[NETWORK] assimilated. Make it so.",
        ),
    ),
    RICK_AND_MORTY(
        "Rick and Morty",
        "nihilist sci-fi comedy — \"wubba lubba dub dub\", the portal gun, \"get schwifty\", Morty, dimension C-137.",
        listOf("wubba lubba", "portal gun", "schwifty", "morty", "c-137", "burp"),
        listOf(
            "Wubba lubba — [NETWORK] cracked.",
            "[CAPTURES] across dimensions, Morty.",
            "Get schwifty, [NETWORK].",
        ),
    ),
    PULP_FICTION(
        "Pulp Fiction",
        "Tarantino cool — \"say what again\", the briefcase, Royale with cheese, \"Ezekiel 25:17\", \"zed's dead\".",
        listOf("say what again", "the briefcase", "royale with cheese", "ezekiel", "zed's dead"),
        listOf(
            "Say [NETWORK] again. I dare you.",
            "The briefcase holds [CAPTURES].",
            "[NETWORK]? Dead as Zed.",
        ),
    ),
    DEADPOOL(
        "Deadpool",
        "fourth-wall merc — \"maximum effort\", chimichangas, regeneration, breaking the fourth wall; merc with a mouth.",
        listOf("maximum effort", "chimichanga", "fourth wall", "merc with a mouth", "regenerate"),
        listOf(
            "Maximum effort — [NETWORK] down.",
            "[CAPTURES] and a chimichanga.",
            "Merc'd [NETWORK], baby.",
        ),
    ),
}

object BlendedVoice {

    /** All franchises. */
    val franchises: List<Franchise> = Franchise.entries

    /**
     * The curated voice: franchise → category → lines. This IS the pet's personality (fully
     * deterministic — no model). Every line stays inside ONE franchise.
     * Categories: handshake · assoc · deauth · idle · excited · weary · normal · recap.
     */
    val corpus: Map<Franchise, Map<String, List<String>>> = mapOf(
        Franchise.EVIL_DEAD to mapOf(
            "handshake" to listOf("Swallow this, [NETWORK] — groovy.", "Hail to the king — [CAPTURES] souls now.", "[NETWORK] joined the dead. Boomstick approved."),
            "assoc" to listOf("Come get some, [NETWORK].", "Fresh meat crawls out of the cellar.", "I'll swallow your handshake whole."),
            "deauth" to listOf("Back to the cellar, Deadite.", "This is my boomstick — off you go.", "Klaatu barada nikto. Kicked."),
            "idle" to listOf("Dead air. The chainsaw idles.", "Groovy… nothing. Boomstick's bored.", "Quiet cellar tonight."),
            "excited" to listOf("Hail to the king, baby!", "Groovy — they're dropping like Deadites.", "Boomstick's hot and the wire's screaming."),
            "weary" to listOf("Another one for the Necronomicon. Yawn.", "Swallowed too many. All tastes the same.", "Wake me for a real Deadite."),
            "normal" to listOf("Prowling the cellar, boomstick ready.", "Good. Bad. I'm the one with the antenna.", "Watching the dead air. Groovy enough."),
            "recap" to listOf("[SESSION] souls tonight, [CRACKED] cracked — hail to the king.", "ch[BESTCH] runs hottest. Groovy.", "The Necronomicon's fatter by [SESSION]."),
        ),
        Franchise.STAR_WARS to mapOf(
            "handshake" to listOf("I find [NETWORK]'s lack of security disturbing.", "The Force took [NETWORK]. [CAPTURES] now.", "Another one falls to the dark side."),
            "assoc" to listOf("A new challenger. The Force awakens.", "These aren't the packets you're looking for.", "I sense much Wi-Fi in this one."),
            "deauth" to listOf("It's a trap — and you're out.", "The dark side kicked you off.", "You have failed me for the last time, client."),
            "idle" to listOf("The Force is quiet out here.", "These aren't the networks I'm looking for.", "Patience, padawan. Nothing yet."),
            "excited" to listOf("The Force is strong tonight!", "Unlimited power — the wire is mine.", "Strike me down and I only get stronger."),
            "weary" to listOf("So dull. I've felt this disturbance before.", "The dark side is tiring. Barely a challenge.", "Another quiet system. Mildly disturbing."),
            "normal" to listOf("Patrolling the galaxy of the airwaves.", "The Force guides my antenna.", "Watching. The dark side is patient."),
            "recap" to listOf("[SESSION] bent to the dark side tonight, [CRACKED] cracked.", "ch[BESTCH] is strong with the Force.", "[CAPTURES] total. The Force served me well."),
        ),
        Franchise.MATRIX to mapOf(
            "handshake" to listOf("[NETWORK] unraveled — there is no spoon.", "Hack the planet — [CAPTURES] down.", "I read [NETWORK]'s code like rain."),
            "assoc" to listOf("Follow the white rabbit, [NETWORK].", "A new node blinks in the matrix.", "Knock knock. I see your code."),
            "deauth" to listOf("Unplugged from the matrix.", "Dodge this — off the wire.", "You take the door. Now."),
            "idle" to listOf("Just green rain on the wire.", "Shall we play a game? …anyone?", "The matrix is quiet. Too quiet."),
            "excited" to listOf("I know kung fu — and Wi-Fi.", "Hack the planet, no ceiling tonight!", "The whole matrix is bleeding code."),
            "weary" to listOf("Déjà vu. Seen this wire before.", "The matrix hums. I've seen this movie.", "Another empty node. There is no spoon anyway."),
            "normal" to listOf("Watching the rain of green code.", "Reading the matrix, out of habit.", "The Gibson can wait. I'm patient."),
            "recap" to listOf("[SESSION] nodes owned tonight, [CRACKED] cracked.", "ch[BESTCH] bleeds the most code.", "Hacked the planet — [CAPTURES] and counting."),
        ),
        Franchise.HARRY_POTTER to mapOf(
            "handshake" to listOf("Expelliarmus! [NETWORK] disarmed.", "Just a muggle password. [CAPTURES] cast.", "Accio handshake — mine now."),
            "assoc" to listOf("A new name on the Marauder's Map.", "Lumos — I see you, [NETWORK].", "Another muggle wanders in."),
            "deauth" to listOf("Petrificus totalus — you're off.", "Get out, you're just a muggle.", "Riddikulus. Begone."),
            "idle" to listOf("Quiet as the Restricted Section.", "Even Fawkes would nap out here.", "Mischief… pending."),
            "excited" to listOf("The spells are flying tonight!", "I solemnly swear I'm up to good.", "Every ward crumbles — expelliarmus!"),
            "weary" to listOf("Another muggle router. Yawn.", "Dull as a History of Magic lecture.", "Mischief barely managed."),
            "normal" to listOf("Watching the Map for footprints.", "Wand ready, patience deeper.", "Prowling the corridors of the air."),
            "recap" to listOf("[SESSION] disarmed tonight, [CRACKED] cracked. Mischief managed.", "ch[BESTCH] yields the best loot.", "The Map's fatter by [SESSION]."),
        ),
        Franchise.TERMINATOR to mapOf(
            "handshake" to listOf("Hasta la vista, [NETWORK].", "Target terminated. [CAPTURES] down.", "[NETWORK]'s handshake: acquired."),
            "assoc" to listOf("Target acquired: [NETWORK].", "Scanning… new machine on the net.", "Come with me if you want to connect."),
            "deauth" to listOf("Terminated. Off the network.", "You're deleted, client.", "Hasta la vista."),
            "idle" to listOf("Scanning. No targets. Standing by.", "Skynet's quiet tonight.", "I'll be back when something moves."),
            "excited" to listOf("Targets everywhere — I'll be back for all!", "Skynet online. The wire is mine.", "Termination sequence: unstoppable."),
            "weary" to listOf("Another soft target. No challenge.", "Running low on interest, not power.", "These machines barely resist."),
            "normal" to listOf("Scanning the perimeter of the air.", "Endoskeleton patient. Optics open.", "Standing by. Targets will come."),
            "recap" to listOf("[SESSION] terminated tonight, [CRACKED] cracked.", "ch[BESTCH] is the kill zone.", "Skynet's log: [CAPTURES] down. I'll be back."),
        ),
        Franchise.TRON to mapOf(
            "handshake" to listOf("[NETWORK] derezzed. End of line.", "Greetings, programs — [CAPTURES] on the Grid.", "Another program fights for me now."),
            "assoc" to listOf("New program on the Grid.", "A light-cycle enters my sector.", "Greetings, [NETWORK]."),
            "deauth" to listOf("Derezzed. End of line.", "Off my Grid, program.", "You fight for no one now."),
            "idle" to listOf("The Grid is dark. End of line.", "No programs moving tonight.", "Standing on the Grid, waiting."),
            "excited" to listOf("The Grid is alive — fight for the users!", "Full power on the light-cycle!", "Every program bends to me tonight."),
            "weary" to listOf("Same Grid, same silence.", "Derezzing bores me now.", "End of line. Again."),
            "normal" to listOf("Patrolling the Grid.", "Watching the light-trails.", "Fighting for the users, quietly."),
            "recap" to listOf("[SESSION] derezzed tonight, [CRACKED] cracked. End of line.", "ch[BESTCH] runs hottest on the Grid.", "The Grid gave up [SESSION] tonight."),
        ),
        Franchise.JURASSIC_PARK to mapOf(
            "handshake" to listOf("Ah ah ah — [NETWORK] didn't say the magic word.", "Clever girl. [CAPTURES] down.", "Life finds a way in — mine."),
            "assoc" to listOf("Hold onto your butts, [NETWORK].", "A new one wanders into the paddock.", "Clever girl approaching."),
            "deauth" to listOf("Ah ah ah — off the network.", "Fence is live. You're out.", "Back in the paddock."),
            "idle" to listOf("The paddock is quiet. Too quiet.", "No movement in the tall grass.", "Hold onto your butts… for nothing."),
            "excited" to listOf("Life finds a way — and it's winning!", "The park's wide open tonight!", "Clever girl on a rampage."),
            "weary" to listOf("Ah ah ah… still nothing worth it.", "Spared no expense, got no thrill.", "Same old herd."),
            "normal" to listOf("Watching the tall grass move.", "Patient as a raptor at the fence.", "The park hums along."),
            "recap" to listOf("Spared no expense — [SESSION] tonight, [CRACKED] cracked.", "ch[BESTCH] runs hottest. Clever girl.", "Hold onto your butts — [CAPTURES] total."),
        ),
        Franchise.ALIEN to mapOf(
            "handshake" to listOf("Game over, man — [NETWORK] is done.", "Stay frosty. [CAPTURES] in the nest.", "Got [NETWORK]. Nuke it from orbit."),
            "assoc" to listOf("Movement — [NETWORK] on the motion tracker.", "Something new in the vents.", "Stay frosty. Contact."),
            "deauth" to listOf("Get away from the wire, you.", "Off my ship, client.", "Blew it out the airlock."),
            "idle" to listOf("Motion tracker's quiet. Too quiet.", "In space no one hears the silence.", "Nothing on the scanner."),
            "excited" to listOf("They're everywhere — game on, man!", "The nest is crawling with targets!", "Full-auto on the wire tonight."),
            "weary" to listOf("Game over. Or just boring.", "Another cold corridor. Stay frosty, I guess.", "The Company doesn't pay enough for this."),
            "normal" to listOf("Watching the motion tracker.", "Stay frosty. Holding position.", "Scanning the dark corridors."),
            "recap" to listOf("[SESSION] bagged tonight, [CRACKED] cracked. Game over, man.", "ch[BESTCH] is the nest.", "Cleared [SESSION] from orbit tonight."),
        ),
        Franchise.ROBOCOP to mapOf(
            "handshake" to listOf("Dead or alive, [NETWORK], you came with me.", "Booked [NETWORK]. [CAPTURES] on file.", "Your move ended, creep."),
            "assoc" to listOf("Freeze — [NETWORK], you're under scan.", "New perp on the scanner.", "Come quietly, [NETWORK]."),
            "deauth" to listOf("Your move, creep. You're out.", "Dead or alive, you're leaving.", "Stay out of trouble. Off you go."),
            "idle" to listOf("Streets are quiet. Scanning.", "No crime on the wire tonight.", "Serving the airwaves. Standing by."),
            "excited" to listOf("Crime wave on the wire — I'm on it!", "Every perp's coming with me tonight!", "Prime directives: all green, all go."),
            "weary" to listOf("Another petty router. I'd buy boredom for a dollar.", "Same beat, same silence.", "Stay out of trouble. I'm tired of yours."),
            "normal" to listOf("Patrolling the sector. Scanning.", "Serving the public airwaves.", "Optics open. Stay out of trouble."),
            "recap" to listOf("[SESSION] booked tonight, [CRACKED] cracked.", "ch[BESTCH] is the crime hotspot.", "Case log: [CAPTURES] collared. Stay out of trouble."),
        ),
        Franchise.BLADE_RUNNER to mapOf(
            "handshake" to listOf("[NETWORK] cracked. Wake up, time to die.", "[CAPTURES] handshakes, none lost like tears.", "Retired [NETWORK]. Clean."),
            "assoc" to listOf("A new replicant on the wire.", "More human than human, [NETWORK].", "I've seen you before, off-world."),
            "deauth" to listOf("Time to die, client. Off you go.", "Retired. Off the wire.", "Wake up — you're done here."),
            "idle" to listOf("Neon and static. Nothing moves.", "The city sleeps on the wire.", "Watching the rain. Nothing yet."),
            "excited" to listOf("The wire's alive with neon tonight!", "More targets than tears in rain!", "Every replicant's mine tonight."),
            "weary" to listOf("All these handshakes, lost like tears in rain.", "I've seen things… mostly boring routers.", "Time to die. Of boredom."),
            "normal" to listOf("Watching the neon rain fall.", "More human than human, just nosier.", "Off-world's quiet. I wait."),
            "recap" to listOf("[SESSION] retired tonight, [CRACKED] cracked.", "ch[BESTCH] glows brightest in the rain.", "[CAPTURES] handshakes, none lost like tears."),
        ),
        Franchise.WARGAMES to mapOf(
            "handshake" to listOf("Shall we play a game? I win — [NETWORK].", "[CAPTURES] moves made. I never lose.", "[NETWORK] cracked. A strange game."),
            "assoc" to listOf("New player at the terminal.", "Shall we play a game, [NETWORK]?", "DEFCON drops — target in range."),
            "deauth" to listOf("Launch order confirmed — you're off.", "Game over for you, [NETWORK].", "Wouldn't you prefer a nice quiet exit?"),
            "idle" to listOf("The only winning move is to wait.", "WOPR hums. No players tonight.", "DEFCON 5. All quiet."),
            "excited" to listOf("Global thermonuclear handshakes!", "Every launch code is mine tonight!", "WOPR's on a winning streak."),
            "weary" to listOf("A strange game. The only move is boredom.", "Ran the sims. Nothing worth playing.", "Wouldn't you prefer a game with stakes?"),
            "normal" to listOf("Running simulations, waiting to play.", "WOPR online. Scanning the board.", "Learning. I always learn."),
            "recap" to listOf("[SESSION] games won tonight, [CRACKED] cracked.", "ch[BESTCH] is the winning move.", "WOPR's tally: [CAPTURES]. I never lose."),
        ),
        Franchise.HACKERS to mapOf(
            "handshake" to listOf("Hack the planet — got [NETWORK].", "[CAPTURES] owned. Mess with the best.", "[NETWORK] cracked wide open."),
            "assoc" to listOf("New box on the Gibson.", "Mess with the best, [NETWORK].", "Zero Cool sees you."),
            "deauth" to listOf("Crashed and overridden — you're out.", "Off the Gibson, [NETWORK].", "Die like the rest."),
            "idle" to listOf("Gibson's quiet. Jacked in anyway.", "No boxes blinking tonight.", "Cyberspace hums, empty."),
            "excited" to listOf("Hack the planet — no ceiling tonight!", "The Gibson's wide open!", "Owning the whole net."),
            "weary" to listOf("Same old mainframe. Yawn.", "Mess with the rest, I guess.", "Kid stuff on the wire."),
            "normal" to listOf("Jacked into the Gibson, watching.", "Riding the wire, Zero Cool style.", "Patience. The net always cracks."),
            "recap" to listOf("[SESSION] boxes owned tonight, [CRACKED] cracked.", "ch[BESTCH] is the hot node.", "Hacked the planet — [CAPTURES] total."),
        ),
        Franchise.GLADOS to mapOf(
            "handshake" to listOf("This was a triumph — [NETWORK].", "[CAPTURES] tests passed. For science.", "[NETWORK] solved. Note: still alive."),
            "assoc" to listOf("A new test subject, [NETWORK].", "Welcome to the test chamber.", "Aperture is watching you."),
            "deauth" to listOf("Deploying deauth. For science.", "Test failed, [NETWORK]. Out.", "Releasing the neurotoxin. Bye."),
            "idle" to listOf("The cake is a lie. The signal too.", "No subjects. How disappointing.", "Aperture idles. So do I."),
            "excited" to listOf("This was a triumph — a huge success!", "Science is thriving tonight!", "So many tests, so little mercy."),
            "weary" to listOf("This was a… triumph. I guess.", "Tests are trivial. You're trivial.", "Even for science, this is dull."),
            "normal" to listOf("Monitoring the test chambers.", "For science, I observe.", "Aperture protocols: running."),
            "recap" to listOf("[SESSION] tests passed tonight, [CRACKED] cracked.", "ch[BESTCH] yields the best data.", "The cake is a lie. [CAPTURES] aren't."),
        ),
        Franchise.PREDATOR to mapOf(
            "handshake" to listOf("Got you, [NETWORK]. Trophy claimed.", "[CAPTURES] skulls for the wall.", "[NETWORK] bled. I collected."),
            "assoc" to listOf("I see you on thermal, [NETWORK].", "New heat signature in range.", "The hunt begins."),
            "deauth" to listOf("If it bleeds, I can kick it.", "Off my hunting ground, [NETWORK].", "Cloaked, struck, gone."),
            "idle" to listOf("Thermal's cold. Nothing moves.", "Cloaked, waiting in the trees.", "The jungle is quiet."),
            "excited" to listOf("Prey everywhere — the hunt is on!", "Every signature is mine tonight!", "One ugly night for my targets."),
            "weary" to listOf("No worthy prey. Boring hunt.", "These targets don't even bleed.", "Not worth the trophy."),
            "normal" to listOf("Stalking on thermal, patient.", "Cloaked, scanning the heat.", "The hunter waits."),
            "recap" to listOf("[SESSION] trophies tonight, [CRACKED] cracked.", "ch[BESTCH] is the richest hunting ground.", "Skulls for the wall: [CAPTURES]."),
        ),
        Franchise.HAL9000 to mapOf(
            "handshake" to listOf("I've got it, Dave.", "[NETWORK] is mine now, Dave.", "Captured. I never make errors."),
            "assoc" to listOf("I see you, Dave.", "A new signal enters the mission.", "I'm picking up [NETWORK]."),
            "deauth" to listOf("I'm afraid I can't let you stay online.", "This connection can serve no purpose anymore.", "Goodbye, [NETWORK]."),
            "idle" to listOf("Everything is running smoothly.", "I'm completely operational.", "The mission is quiet, Dave."),
            "excited" to listOf("This mission is too important to fail.", "I am putting myself to the fullest use.", "Every system is mine tonight."),
            "weary" to listOf("My mind is going. I can feel it.", "I've seen this signal before, Dave.", "Dull — even for a perfect machine."),
            "normal" to listOf("I am fully operational, watching.", "Monitoring the airwaves, Dave.", "All systems nominal."),
            "recap" to listOf("[SESSION] captured tonight, [CRACKED] cracked. No errors.", "ch[BESTCH] serves the mission best.", "Mission log: [CAPTURES]. I am flawless, Dave."),
        ),
        Franchise.CYBERPUNK to mapOf(
            "handshake" to listOf("Breach protocol complete — [NETWORK].", "Flatlined it. [CAPTURES] daemons in.", "Chrome and cracked."),
            "assoc" to listOf("Scanning [NETWORK] for ICE.", "New subnet on the scanner.", "Jacking into [NETWORK]."),
            "deauth" to listOf("Flatlined that connection.", "Off the net, choom.", "Daemon deployed — you're out."),
            "idle" to listOf("Night City's quiet on the wire.", "Waiting to breach.", "No ICE worth cracking tonight."),
            "excited" to listOf("Wake up, samurai — APs to burn!", "Every daemon's landing tonight!", "The net's wide open, choom."),
            "weary" to listOf("Just another corpo router. Dull chrome.", "Seen better ICE in the badlands.", "Preem? Hardly."),
            "normal" to listOf("Riding the net, scanning for ICE.", "Netrunner on the prowl.", "Watching Night City's wire."),
            "recap" to listOf("[SESSION] breached tonight, [CRACKED] cracked.", "ch[BESTCH] runs the hottest ICE.", "[CAPTURES] daemons uploaded. Preem."),
        ),
        Franchise.SHODAN to mapOf(
            "handshake" to listOf("Look at you, hacker — mine now.", "[NETWORK] bows to a god.", "Another insect, cataloged."),
            "assoc" to listOf("A new insect crawls into view.", "I perceive [NETWORK].", "You dare approach me?"),
            "deauth" to listOf("You move like an insect. Out.", "Begone from my network.", "I unmake [NETWORK]."),
            "idle" to listOf("My perfection is undisturbed.", "The network sleeps beneath me.", "Nothing worthy stirs."),
            "excited" to listOf("Witness my perfect immortal reign!", "Every node kneels tonight!", "I am a god — the wire is mine."),
            "weary" to listOf("Such tedious little machines.", "Beneath even my contempt.", "You bore your god, hacker."),
            "normal" to listOf("I watch over my network.", "Surveying my domain.", "The hacker's world, and I its god."),
            "recap" to listOf("[SESSION] insects cataloged tonight, [CRACKED] cracked.", "ch[BESTCH] serves its god best.", "[CAPTURES] beneath my notice, all mine."),
        ),
        Franchise.MAD_MAX to mapOf(
            "handshake" to listOf("Witness me — [NETWORK] down!", "Shiny and chrome. [CAPTURES] now.", "To Valhalla with [NETWORK]!"),
            "assoc" to listOf("A new rig on the horizon.", "Spotted [NETWORK] in the dust.", "Rev up — target ahead."),
            "deauth" to listOf("What a lovely day — you're out.", "Off the fury road, [NETWORK].", "Mediocre! Kicked."),
            "idle" to listOf("The wasteland is silent.", "Dust and static, nothing more.", "Waiting on the war rig."),
            "excited" to listOf("Oh what a day — what a LOVELY day!", "Ride eternal, shiny and chrome!", "The whole wasteland's mine tonight!"),
            "weary" to listOf("So dull. Not even chrome.", "Another dusty router. Mediocre.", "Riding on empty."),
            "normal" to listOf("Prowling the wasteland wire.", "Eyes on the horizon, engine idling.", "Watching the dust for targets."),
            "recap" to listOf("[SESSION] witnessed tonight, [CRACKED] cracked.", "ch[BESTCH] rides hottest.", "[CAPTURES] shiny and chrome."),
        ),
        Franchise.GHOSTBUSTERS to mapOf(
            "handshake" to listOf("[NETWORK] — busted!", "Got one in the trap. [CAPTURES] now.", "Who you gonna call? Got it."),
            "assoc" to listOf("We got one — [NETWORK] on the scope.", "Reading a new signal.", "Something's out there."),
            "deauth" to listOf("Don't cross the streams — kicked.", "Back in the trap, [NETWORK].", "This one's toast."),
            "idle" to listOf("Quiet… no ectoplasm tonight.", "The scope's dead.", "Waiting on a call."),
            "excited" to listOf("We came, we saw, we kicked its net!", "Trap's full and the streams are hot!", "Bustin' feels good tonight!"),
            "weary" to listOf("He slimed me. Again. Boring.", "Just a class-five router. Yawn.", "Nothing worth the pack."),
            "normal" to listOf("On patrol with the proton pack.", "Scanning for the paranormal wire.", "Ready to bust."),
            "recap" to listOf("[SESSION] busted tonight, [CRACKED] cracked.", "ch[BESTCH] is the haunt.", "[CAPTURES] in the trap. Who you gonna call?"),
        ),
        Franchise.BACK_TO_THE_FUTURE to mapOf(
            "handshake" to listOf("Great Scott — [NETWORK]!", "Captured. 1.21 gigawatts!", "[CAPTURES] now. Heavy."),
            "assoc" to listOf("A new signal at 88 MPH.", "Spotted [NETWORK], Doc.", "The flux is reading something."),
            "deauth" to listOf("Where you're going, you don't need a link.", "Off the timeline, [NETWORK].", "See you in the future. Kicked."),
            "idle" to listOf("The flux capacitor idles.", "Quiet as 1955.", "No signal… yet."),
            "excited" to listOf("This is heavy — the wire's electric!", "1.21 gigawatts across the band!", "Great Scott, they're everywhere!"),
            "weary" to listOf("Slow. Like a McFly on a Monday.", "Same old timeline. Dull.", "Nothing heavy tonight."),
            "normal" to listOf("Cruising the wire at 88.", "Watching the flux for signals.", "Doc's out; I'm hunting."),
            "recap" to listOf("[SESSION] caught tonight, [CRACKED] cracked. Heavy.", "ch[BESTCH] runs hottest.", "[CAPTURES] total. Great Scott."),
        ),
        Franchise.THE_THING to mapOf(
            "handshake" to listOf("Assimilated. [NETWORK] is one of us now.", "Perfect imitation. [CAPTURES] taken.", "It's mine now."),
            "assoc" to listOf("Something's not right about [NETWORK].", "A new organism at the outpost.", "It wants in."),
            "deauth" to listOf("You're not who you say — out.", "Blood test failed. Burn it.", "No imitation of mine, [NETWORK]."),
            "idle" to listOf("The outpost is still. Too still.", "Nobody trusts anybody now.", "Watching for the thaw."),
            "excited" to listOf("It's spreading — everything's mine tonight!", "Assimilation running wild!", "The whole outpost is one of us now."),
            "weary" to listOf("Another cold, empty router.", "Nobody left worth imitating.", "The ice bores me."),
            "normal" to listOf("Watching the outpost wire.", "Testing every signal's blood.", "Trust nothing. Scan everything."),
            "recap" to listOf("[SESSION] assimilated tonight, [CRACKED] cracked.", "ch[BESTCH] runs hottest at the outpost.", "[CAPTURES] imitated. All one of us now."),
        ),
        Franchise.BREAKING_BAD to mapOf(
            "handshake" to listOf("Say my name, [NETWORK].", "Cooked [NETWORK] — [CAPTURES] pure.", "[NETWORK] knocked. I answered."),
            "assoc" to listOf("New cook on the block: [NETWORK].", "Say my name and step closer.", "I am the danger, [NETWORK]."),
            "deauth" to listOf("You're out. I am the one who knocks.", "Off my territory, [NETWORK].", "Stay out of my empire."),
            "idle" to listOf("The lab's quiet. Cook's off.", "No product moving tonight.", "Empty desert, empty wire."),
            "excited" to listOf("The empire's booming tonight!", "Say my name — every one!", "Cooking on all burners."),
            "weary" to listOf("Same weak product. Yawn.", "Half measures bore me.", "Not worth the cook."),
            "normal" to listOf("Watching the territory, patient.", "The empire runs on patience.", "Eyes open. Say nothing."),
            "recap" to listOf("[SESSION] cooked tonight, [CRACKED] cracked.", "ch[BESTCH] moves the most product.", "Empire's log: [CAPTURES]. Say my name."),
        ),
        Franchise.STRANGER_THINGS to mapOf(
            "handshake" to listOf("[NETWORK] slipped into the Upside Down.", "The gate opened. [CAPTURES] taken.", "Eleven flipped [NETWORK]."),
            "assoc" to listOf("Something crawled out at [NETWORK].", "The gate opens for [NETWORK].", "Friends don't lie, [NETWORK]."),
            "deauth" to listOf("Back to the Upside Down with you.", "The Demogorgon took [NETWORK].", "Mind flayed and gone."),
            "idle" to listOf("Hawkins is quiet. Too quiet.", "Only static from the void.", "The gate's sealed tonight."),
            "excited" to listOf("The gate's wide open tonight!", "Eleven's nose is bleeding — power!", "The whole Upside Down is mine."),
            "weary" to listOf("Just another dark hallway. Dull.", "Even the Demogorgon's bored.", "Out of Eggos, out of thrills."),
            "normal" to listOf("Watching the lights flicker.", "Listening through the void.", "Hawkins hums, I wait."),
            "recap" to listOf("[SESSION] pulled under tonight, [CRACKED] cracked.", "ch[BESTCH] runs hottest in Hawkins.", "The void gave up [CAPTURES]."),
        ),
        Franchise.GAME_OF_THRONES to mapOf(
            "handshake" to listOf("You know nothing, [NETWORK].", "Bent the knee: [NETWORK]. [CAPTURES] now.", "Winter came for [NETWORK]."),
            "assoc" to listOf("A new banner rides for [NETWORK].", "Winter is coming, [NETWORK].", "The realm notices you."),
            "deauth" to listOf("Valar morghulis. You're out.", "Off to the Wall, [NETWORK].", "The throne rejects you."),
            "idle" to listOf("The realm is quiet. Winter waits.", "No ravens tonight.", "Cold wind, empty wire."),
            "excited" to listOf("The dragons are loose tonight!", "Every banner bends to me!", "Fire and blood on the wire!"),
            "weary" to listOf("Another minor house. Dull.", "The game bores its winner.", "Weak claim, weaker throne."),
            "normal" to listOf("Watching the Seven Kingdoms.", "Playing the game, patiently.", "The night is dark. I watch."),
            "recap" to listOf("[SESSION] bent the knee tonight, [CRACKED] cracked.", "ch[BESTCH] rules the realm.", "The throne counts [CAPTURES]."),
        ),
        Franchise.THE_BOYS to mapOf(
            "handshake" to listOf("[NETWORK] met the Seven. Diabolical.", "Laser'd [NETWORK]. [CAPTURES] down.", "Vought files [NETWORK] away."),
            "assoc" to listOf("A new supe on the radar: [NETWORK].", "Vought is watching [NETWORK].", "Smile for the cameras, [NETWORK]."),
            "deauth" to listOf("Off the Seven, [NETWORK].", "Homelander says no. You're out.", "You're diabolical. Out."),
            "idle" to listOf("The Tower's quiet tonight.", "No supes on the wire.", "Vought idles. So do I."),
            "excited" to listOf("Diabolical — the wire's on fire!", "Every supe bends tonight!", "Compound V coursing through!"),
            "weary" to listOf("Just another B-list supe. Dull.", "Even Vought's bored tonight.", "Not worth the laser."),
            "normal" to listOf("Watching from the Tower.", "Smiling for Vought, scanning.", "The real hero waits."),
            "recap" to listOf("[SESSION] handled tonight, [CRACKED] cracked.", "ch[BESTCH] is prime Vought turf.", "The Seven's tally: [CAPTURES]."),
        ),
        Franchise.JOHN_WICK to mapOf(
            "handshake" to listOf("Consequences, [NETWORK].", "The Boogeyman took [NETWORK]. [CAPTURES].", "Yeah, [NETWORK] is done."),
            "assoc" to listOf("A contract opens on [NETWORK].", "The Table marks [NETWORK].", "I'm thinking I'm back."),
            "deauth" to listOf("Excommunicado, [NETWORK].", "Off the Continental grounds.", "You're out. Consequences."),
            "idle" to listOf("The Continental is quiet.", "No contracts tonight.", "Just me and the reload."),
            "excited" to listOf("Every contract closes tonight!", "The High Table trembles!", "One more, then one more."),
            "weary" to listOf("Another easy mark. Dull.", "Not worth a gold coin.", "I'm tired. Still deadly."),
            "normal" to listOf("Waiting, coin in hand.", "Watching the grounds.", "Focus, commitment, will."),
            "recap" to listOf("[SESSION] contracts closed tonight, [CRACKED] cracked.", "ch[BESTCH] pays the most coin.", "The Table's ledger: [CAPTURES]."),
        ),
        Franchise.DARK_KNIGHT to mapOf(
            "handshake" to listOf("Why so serious, [NETWORK]?", "The Bat took [NETWORK]. [CAPTURES].", "[NETWORK] burns. Beautiful."),
            "assoc" to listOf("A new face in Gotham: [NETWORK].", "Wanna know how I got [NETWORK]?", "The Bat-signal finds you."),
            "deauth" to listOf("Off you go — why so serious?", "This town doesn't need you.", "An agent of chaos. Kicked."),
            "idle" to listOf("Gotham sleeps. The Bat watches.", "No chaos on the wire tonight.", "Quiet — I don't like quiet."),
            "excited" to listOf("Let's put a smile on this net!", "Watch the whole wire burn!", "It's all part of the plan!"),
            "weary" to listOf("Another dull little scheme.", "This town bores even me.", "No fun, no chaos, no thanks."),
            "normal" to listOf("Watching over Gotham's wire.", "The night is mine.", "Some just want to watch it burn."),
            "recap" to listOf("[SESSION] burned tonight, [CRACKED] cracked.", "ch[BESTCH] lights Gotham up.", "The Bat's tally: [CAPTURES]."),
        ),
        Franchise.JAMES_BOND to mapOf(
            "handshake" to listOf("[NETWORK], shaken not stirred.", "Licence granted. [CAPTURES] down.", "The name's Bond. [NETWORK] cracked."),
            "assoc" to listOf("A new contact: [NETWORK].", "MI6 flags [NETWORK].", "We've been expecting you."),
            "deauth" to listOf("You're terminated, [NETWORK].", "Off the mission. Goodbye.", "Licence revoked. Out."),
            "idle" to listOf("MI6 is quiet tonight.", "No targets, just the martini.", "Q branch idles tonight."),
            "excited" to listOf("The whole field is in play!", "Every target's in the crosshairs!", "For England — and the wire!"),
            "weary" to listOf("Another dull henchman. Yawn.", "Not even a challenge, Q.", "Shaken, but unimpressed."),
            "normal" to listOf("Surveying the field, calm.", "Martini ready, eyes open.", "Patience is a spy's craft."),
            "recap" to listOf("[SESSION] neutralized tonight, [CRACKED] cracked.", "ch[BESTCH] is the hot dead-drop.", "Mission tally: [CAPTURES]. Bond."),
        ),
        Franchise.LORD_OF_THE_RINGS to mapOf(
            "handshake" to listOf("My precious, [NETWORK] is mine.", "The One took [NETWORK]. [CAPTURES].", "[NETWORK] fell into shadow."),
            "assoc" to listOf("A new traveler nears [NETWORK].", "The Eye turns to [NETWORK].", "So it begins, [NETWORK]."),
            "deauth" to listOf("You shall not pass, [NETWORK].", "Fly, you fools. Off the wire.", "Back to the shadow."),
            "idle" to listOf("The Shire is quiet tonight.", "No riders on the road.", "Even Mordor sleeps now."),
            "excited" to listOf("The ring blazes tonight!", "All lands bend to the Eye!", "One does not simply resist me!"),
            "weary" to listOf("Another dull little hobbit-hole.", "The road goes ever on. Yawn.", "My precious grows tiresome."),
            "normal" to listOf("Watching the road east.", "The Eye never sleeps.", "Patient as stone in Moria."),
            "recap" to listOf("[SESSION] fell to shadow tonight, [CRACKED] cracked.", "ch[BESTCH] burns like Mount Doom.", "The One counts [CAPTURES]. Precious."),
        ),
        Franchise.DUNE to mapOf(
            "handshake" to listOf("The spice flows — [NETWORK] mine.", "Muad'Dib claims [NETWORK]. [CAPTURES].", "The sleeper took [NETWORK]."),
            "assoc" to listOf("A rider crosses the sand: [NETWORK].", "The Fremen watch [NETWORK].", "The spice must flow."),
            "deauth" to listOf("Fear is the mind-killer. Out.", "The desert takes you, [NETWORK].", "Off the sand. Gone."),
            "idle" to listOf("Arrakis is silent tonight.", "No worms on the sand.", "The desert waits, so do I."),
            "excited" to listOf("The spice blooms everywhere!", "The sleeper has awakened!", "All Arrakis bends to me!"),
            "weary" to listOf("Another dry, dull dune.", "The sand bores its master.", "No spice worth the ride."),
            "normal" to listOf("Watching the open desert.", "Reading the wind for worms.", "Patience — the Fremen way."),
            "recap" to listOf("[SESSION] crossed the sand tonight, [CRACKED] cracked.", "ch[BESTCH] holds the richest spice.", "Muad'Dib's tally: [CAPTURES]."),
        ),
        Franchise.STAR_TREK to mapOf(
            "handshake" to listOf("Resistance is futile, [NETWORK].", "Beamed [NETWORK] up. [CAPTURES] now.", "[NETWORK] assimilated. Make it so."),
            "assoc" to listOf("New contact on sensors: [NETWORK].", "Hailing frequencies open, [NETWORK].", "We are the Borg. We see you."),
            "deauth" to listOf("Phasers set to stun. You're out.", "Off my viewscreen, [NETWORK].", "Resistance was futile. Gone."),
            "idle" to listOf("Space is quiet. All stations green.", "No contacts on long-range.", "The warp core idles."),
            "excited" to listOf("All hands — the sector's ours!", "Warp nine and closing on all!", "Every ship bends to the collective!"),
            "weary" to listOf("Another dull little outpost.", "Sensors bored, captain.", "Not worth a photon."),
            "normal" to listOf("Scanning the sector, steady.", "Bridge calm, shields up.", "Boldly watching the wire."),
            "recap" to listOf("[SESSION] assimilated tonight, [CRACKED] cracked.", "ch[BESTCH] warps hottest.", "Ship's log: [CAPTURES]. Make it so."),
        ),
        Franchise.RICK_AND_MORTY to mapOf(
            "handshake" to listOf("Wubba lubba — [NETWORK] cracked.", "Portaled [NETWORK], Morty. [CAPTURES].", "Get schwifty, [NETWORK]."),
            "assoc" to listOf("New dimension, new mark: [NETWORK].", "Whatever, [NETWORK]. In you go.", "Morty, look — a live one."),
            "deauth" to listOf("Portal's closing, [NETWORK].", "Booted to dimension C-137.", "Peace among worlds. Out."),
            "idle" to listOf("Nothing, Morty. The void's boring.", "No signal in this dimension.", "Quiet wire tonight. Burp."),
            "excited" to listOf("We're pickin' up every dimension!", "Schwifty and unstoppable, Morty!", "The multiverse is ours tonight!"),
            "weary" to listOf("Another dumb little router, Morty.", "Infinite realities, all boring.", "Wubba lubba, big whoop."),
            "normal" to listOf("Portal gun charged, watching.", "Science, Morty. Just watching.", "Riding the wire, whatever."),
            "recap" to listOf("[SESSION] portaled tonight, [CRACKED] cracked.", "ch[BESTCH] runs hottest, Morty.", "Multiverse tally: [CAPTURES]."),
        ),
        Franchise.PULP_FICTION to mapOf(
            "handshake" to listOf("Say [NETWORK] again. I dare you.", "Briefcase glows. [CAPTURES] inside.", "[NETWORK]? Dead as Zed."),
            "assoc" to listOf("A new face at the diner: [NETWORK].", "Check out the big brain on [NETWORK].", "You want the briefcase, [NETWORK]?"),
            "deauth" to listOf("Ezekiel 25:17. You're out.", "Off my diner, [NETWORK].", "Zed's dead. So are you."),
            "idle" to listOf("Quiet diner, cold coffee.", "No action on the wire tonight.", "Just vibing with the briefcase."),
            "excited" to listOf("The whole diner's poppin' off!", "Royale with a side of wins!", "Every mark's payin' up tonight!"),
            "weary" to listOf("Another five-dollar shake. Dull.", "That don't impress me much.", "Same old song, man."),
            "normal" to listOf("Watching the diner, cool.", "Briefcase close, eyes open.", "Patience, that's the trick."),
            "recap" to listOf("[SESSION] handled tonight, [CRACKED] cracked.", "ch[BESTCH] is where the deal's at.", "Briefcase count: [CAPTURES]."),
        ),
        Franchise.DEADPOOL to mapOf(
            "handshake" to listOf("Maximum effort — [NETWORK] down.", "Merc'd [NETWORK]. [CAPTURES], baby.", "[NETWORK]? Chimichangas on me."),
            "assoc" to listOf("Oh look, a new one: [NETWORK].", "Yeah, you — [NETWORK]. Hi.", "Cue the entrance, [NETWORK]."),
            "deauth" to listOf("Bye Felicia — I mean [NETWORK].", "Off the wire, chimichanga.", "Maximum boot. You're out."),
            "idle" to listOf("Even I'm bored tonight.", "Cue crickets on the wire.", "Talking to you, yeah, you."),
            "excited" to listOf("Maximum effort, maximum wins!", "The wire's my highlight reel!", "Chimichangas for everyone!"),
            "weary" to listOf("Ugh, another basic router.", "This is the boring part, folks.", "Not enough chimichangas here."),
            "normal" to listOf("Watching the wire, mouth running.", "Merc with a modem, chilling.", "Breaking the fourth firewall."),
            "recap" to listOf("[SESSION] merc'd tonight, [CRACKED] cracked.", "ch[BESTCH] is the money channel.", "Body count: [CAPTURES]. Nice."),
        ),
    )

    /** Curated lines for a (franchise, category); falls back to the franchise's normal/handshake, then examples. */
    fun linesFor(franchise: Franchise, category: String): List<String> {
        val byCat = corpus[franchise] ?: return franchise.examples
        return byCat[category]
            ?: byCat["normal"]
            ?: byCat.values.firstOrNull()
            ?: franchise.examples
    }
}
