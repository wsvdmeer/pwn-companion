package com.wsvdmeer.pwncompanion.ai

/**
 * The pet's voice — **curated-first**. On a 0.5B model, reliable personality comes from
 * hand-written lines, not generation. So the [Franchise] [corpus] below IS the voice:
 * a large set of single-franchise, in-character lines keyed by (franchise → category).
 * The LLM is *seasoning* on top — it runs occasionally and its output is used only if it
 * passes the franchise + coherence guard (see PwnagotchiViewModel); otherwise a curated
 * line is used. [BuiltinPersonalityEngine] selects from the corpus.
 *
 * ONE franchise is pinned at a time (a "current franchise" that persists for a stretch and
 * rotates on a mood flip — see PwnagotchiViewModel.currentFranchise), so the pet reads as a
 * character *in a mood* rather than franchise-roulette, and never blends two worlds.
 *
 * Substitution tokens (resolved by [BuiltinPersonalityEngine]):
 *   [NETWORK]  → SSID, or "that network"
 *   [CAPTURES] → running handshake count, or "a few"
 *
 * Corpus categories (unified; runtime maps its own vocab onto these — see catKey()):
 *   handshake · assoc · deauth · idle · excited · weary · normal · recap
 */

/** How the pet sounds right now — chosen by the emergent mood, not by the user. */
enum class VoiceTone { HYPE, GRUMPY, WEARY, DEADPAN }

/**
 * The cult-movie worlds the pet draws on — exactly ONE pinned at a time.
 * [cue] steers the LLM; [examples] are same-franchise few-shot; [keywords] are signature
 * tokens the franchise-guard uses to detect (and reject) a line that drifted into another world.
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
}

object BlendedVoice {

    /** Character description injected into the LLM system prompt (kept constant for KV-cache). */
    val persona: String =
        "You are a wisecracking hacker gremlin haunting a Pwnagotchi. You riff in the " +
        "style of cult sci-fi, hacker, action and fantasy films, treating networks as " +
        "prey and captured handshakes as trophies. Each reply channels EXACTLY ONE film " +
        "world (named for you per line); stay fully inside it and NEVER blend two " +
        "franchises in the same line. Let the references leak out naturally — don't " +
        "announce which movie."

    /** All franchises. */
    val franchises: List<Franchise> = Franchise.entries

    /** "> …" status phrases shown while the model generates. */
    val thinking: List<String> = listOf(
        "> loading the boomstick…",
        "> there is no spoon…",
        "> channeling the Force…",
        "> casting Expelliarmus…",
        "> following the white rabbit…",
        "> revving the chainsaw…",
        "> deauth in progress…",
        "> reading from the Necronomicon…",
    )

    /** Map the emergent disposition label to a tone bucket. */
    fun toneFor(disposition: String): VoiceTone = when (disposition.lowercase()) {
        "cocky", "confident"   -> VoiceTone.HYPE
        "frustrated"           -> VoiceTone.GRUMPY
        "restless", "drained"  -> VoiceTone.WEARY
        else                   -> VoiceTone.DEADPAN   // curious, composed, neutral
    }

    /** A short tone directive injected into the (variable) LLM user turn so mood steers voice. */
    fun toneDirective(tone: VoiceTone): String = when (tone) {
        VoiceTone.HYPE    -> "You're riding high — cocky, triumphant, swaggering. Gloat."
        VoiceTone.GRUMPY  -> "You're pissed off — snarling, dark, short-tempered. Complain with menace."
        VoiceTone.WEARY   -> "You're bored and drained — flat, unimpressed, world-weary."
        VoiceTone.DEADPAN -> "You're level — dry, deadpan, quietly sharp."
    }

    /**
     * The curated voice: franchise → category → lines. This is the PRIMARY source of the
     * pet's personality (the LLM only supplements). Every line stays inside ONE franchise.
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
