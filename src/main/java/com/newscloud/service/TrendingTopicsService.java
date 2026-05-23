package com.newscloud.service;

import com.newscloud.model.Article;
import com.newscloud.model.TrendingTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrendingTopicsService {

    private static final Logger log = LoggerFactory.getLogger(TrendingTopicsService.class);

    private static final int MAX_TOPICS = 60;
    private static final int MIN_WORD_LENGTH = 3;
    private static final int MAX_ARTICLES_PER_TOPIC = 20;
    private static final double MERGE_THRESHOLD = 0.35;

    // Frequency thresholds ─────────────────────────────────────────────────────
    /**
     * Single words are ONLY allowed if they are identified as proper nouns.
     * Generic single words ("tech", "health", "company") are never trending topics.
     */
    private static final int MIN_FREQ_UNIGRAM_PROPER = 4;
    /** Bigrams where ≥1 word is a known proper noun */
    private static final int MIN_FREQ_BIGRAM_PROPER = 2;
    /** Bigrams with no proper nouns (e.g. "climate change") need more signal */
    private static final int MIN_FREQ_BIGRAM_GENERIC = 5;
    /** Trigrams are specific by nature — low bar */
    private static final int MIN_FREQ_TRIGRAM = 2;

    // IDF filter: remove phrases present in more than this fraction of all articles
    private static final double IDF_MAX_RATIO = 0.25;

    // Proper-noun detection thresholds
    /** Minimum mid-sentence appearances in descriptions before classifying */
    private static final int PN_MIN_SAMPLES = 2;
    /** Fraction that must be capitalised mid-sentence for word → proper noun */
    private static final double PN_CAP_RATE = 0.75;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        // Articles & determiners
        "a", "an", "the", "this", "that", "these", "those", "each", "every",
        "either", "neither", "any", "all", "both", "some", "such", "no",
        // Prepositions
        "in", "on", "at", "by", "for", "with", "about", "against", "between",
        "into", "through", "during", "before", "after", "above", "below", "to",
        "from", "up", "down", "out", "off", "over", "under", "again", "further",
        "then", "once", "of", "as", "per", "via", "re", "than",
        // Conjunctions & logical words
        "and", "but", "or", "nor", "not", "so", "yet", "also", "although", "because",
        "since", "while", "if", "unless", "until", "when", "where", "which",
        "that", "who", "whom", "whether", "however", "though",
        // Question words
        "how", "why", "what", "when", "where", "who", "whom", "whose", "which",
        // Pronouns
        "i", "me", "my", "myself", "we", "our", "ours", "ourselves",
        "you", "your", "yours", "yourself", "yourselves",
        "he", "him", "his", "himself", "she", "her", "hers", "herself",
        "it", "its", "itself", "they", "them", "their", "theirs", "themselves",
        // Auxiliary & common verbs
        "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having", "do", "does", "did", "doing",
        "will", "would", "could", "should", "may", "might", "must", "shall", "can",
        "get", "got", "gotten", "getting", "let", "put", "set", "go", "goes", "gone",
        "come", "came", "make", "made", "take", "took", "see", "saw", "know", "knew",
        "say", "said", "says", "tell", "told", "use", "used", "want", "like", "think",
        "look", "seem", "need", "try", "ask", "keep", "feel", "become", "leave",
        "call", "help", "turn", "show", "move", "live", "run", "hold", "bring",
        "happen", "write", "provide", "sit", "stand", "lose", "pay", "meet",
        "include", "continue", "raise", "pass", "sell", "require", "report",
        // Common news boilerplate
        "new", "news", "report", "reports", "reported", "reporting",
        "according", "official", "officials", "government", "president", "prime",
        "minister", "people", "country", "countries", "world", "global", "national",
        "international", "local", "public", "private", "state", "federal",
        "year", "years", "time", "times", "day", "days", "week", "weeks",
        "month", "months", "hour", "hours", "ago", "last", "first", "next",
        "more", "most", "other", "many", "much", "less", "few", "own", "same",
        "too", "very", "just", "still", "even", "back", "well", "way", "end",
        "here", "there", "now", "only", "always", "never", "often",
        "old", "big", "small", "high", "low", "long", "far", "early",
        "late", "right", "left", "great", "good", "bad",
        "between", "around", "among", "within", "without", "across", "along",
        "behind", "beyond", "despite", "except", "following", "including",
        "regarding", "concerning", "due", "vs", "amid",
        // RSS / web / subscription boilerplate
        "read", "reading", "click", "watch", "sign", "share", "subscribe", "follow",
        "latest", "breaking", "update", "updates", "full", "more", "top", "former",
        "continues", "continue", "continued", "learn", "getty", "afp",
        "app", "free", "daily", "email", "podcast", "newsletter", "download",
        "edition", "alerts", "alert", "inbox",
        // Cardinal directions (only meaningful as part of compound proper nouns)
        "north", "south", "east", "west", "northern", "southern", "eastern", "western",
        // News source names that bleed into descriptions
        "guardian", "bbc", "cnn", "cbs", "nbc", "abc", "npr", "reuters", "skynews", "euronews",
        // Overly generic media/tech terms
        "social", "media", "platform", "platforms", "online", "digital", "tech",
        // Excluded individuals / companies
        "trump", "donald", "google", "pro",
        // Generic nouns that add no topic signal in any context
        "air", "man", "men", "woman", "women", "person",
        "side", "part", "parts", "kind", "type", "form",
        "point", "points", "level", "levels",
        "number", "numbers", "amount", "amounts", "rate", "rates",
        "fact", "matter", "matters", "problem", "problems",
        "question", "questions", "result", "results", "impact", "role",
        "step", "steps", "decision", "decisions", "action", "actions",
        "effort", "efforts", "claim", "claims",
        // Numbers as words
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "hundred", "thousand", "million", "billion",
        // Days and months
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "june", "july", "august",
        "september", "october", "november", "december"
    ));

    /**
     * Countries, regions and nationalities that are almost always context rather
     * than the trending topic itself. These are filtered as UNIGRAMS only — they
     * can still appear inside bigrams/trigrams (e.g. "Australian Election",
     * "French Open", "Indian Ocean"). Hotspot countries (China, Russia, Iran,
     * Israel, Ukraine …) are intentionally omitted so they can surface when
     * genuinely trending.
     */
    private static final Set<String> GENERIC_PLACES = new HashSet<>(Arrays.asList(
        // Anglosphere
        "australia", "australian", "australians",
        "queensland", "queenslander",
        "victoria", "victorian",
        "nsw", "act",
        "canada", "canadian", "canadians",
        "britain", "british", "england", "english", "scotland", "scottish", "wales", "welsh",
        "newzealand", "zealand",
        // Western Europe
        "france", "french",
        "germany", "german", "germanys",
        "italy", "italian",
        "spain", "spanish",
        "netherlands", "dutch",
        "belgium", "belgian",
        "sweden", "swedish",
        "norway", "norwegian",
        "denmark", "danish",
        "finland", "finnish",
        "switzerland", "swiss",
        "austria", "austrian",
        "portugal", "portuguese",
        "ireland", "irish",
        "poland", "polish",
        "greece", "greek",
        "hungary", "hungarian",
        "romania", "romanian",
        "czechia", "czech",
        "turkey", "turkish",
        // Asia-Pacific
        "japan", "japanese",
        "india", "indian", "indians",
        "indonesia", "indonesian",
        "pakistan", "pakistani",
        "bangladesh", "bangladeshi",
        "philippines", "filipino",
        "vietnam", "vietnamese",
        "thailand", "thai",
        "malaysia", "malaysian",
        "singapore", "singaporean",
        "myanmar", "burmese",
        "korea", "korean",
        "taiwan", "taiwanese",
        // Americas
        "brazil", "brazilian",
        "mexico", "mexican",
        "argentina", "argentine", "argentinian",
        "colombia", "colombian",
        "chile", "chilean",
        "peru", "peruvian",
        "cuba", "cuban",
        "venezuela", "venezuelan",
        "haiti", "haitian",
        // Africa / Middle East
        "nigeria", "nigerian",
        "kenya", "kenyan",
        "egypt", "egyptian",
        "ethiopia", "ethiopian",
        "algeria", "algerian",
        "morocco", "moroccan",
        "ghana", "ghanaian",
        "tanzania", "tanzanian",
        "sudan", "sudanese",
        "somalia", "somali",
        "libya", "libyan",
        "saudi", "emirati",
        "jordan", "jordanian",
        "qatar", "qatari",
        "lebanon", "lebanese",
        "yemen", "yemeni",
        "iraq", "iraqi",
        "syria", "syrian",
        "afghanistan", "afghan",
        // Broad regions
        "europe", "european",
        "africa", "african",
        "asia", "asian",
        "americas", "latin",
        "middle", "american", "western", "eastern",
        // Generic structural/political words — blocked as unigrams,
        // still allowed in bigrams/trigrams (e.g. "Hyde Park", "Supreme Court", "Trade War")
        "park", "house", "court", "hall", "square", "center", "centre",
        "bridge", "tower", "island", "city", "town", "port", "bay", "lake",
        "street", "road", "avenue", "station", "district", "region", "province",
        "party", "union", "council", "assembly", "parliament", "senate", "congress",
        "bank", "fund", "market", "markets",
        "war", "wars", "force", "forces", "crisis", "conflict",
        // Generic nouns useful in bigrams ("Climate Change", "Terror Attack", "Foreign Policy")
        // but too vague as standalone topics
        "change", "changes", "attack", "attacks",
        "deal", "deals", "plan", "plans",
        "policy", "policies", "law", "laws",
        "case", "cases", "issue", "issues",
        "leader", "leaders", "member", "members",
        "move", "moves", "area", "areas", "place", "places"
    ));

    // Common first names — blocked as standalone topics, still allowed in bigrams/trigrams
    // ("John Howard", "Tom Hanks", "Mary Poppins"). Names of places or objects that share
    // spelling (e.g. "Mark" as a currency) are acceptable collateral; full names win anyway.
    private static final Set<String> FIRST_NAMES = new HashSet<>(Arrays.asList(
        // Male
        "james", "john", "robert", "michael", "william", "david", "richard", "joseph",
        "thomas", "charles", "christopher", "daniel", "matthew", "anthony", "mark",
        "steven", "paul", "andrew", "kenneth", "george", "joshua", "kevin", "brian",
        "edward", "timothy", "jason", "jeffrey", "ryan", "jacob", "gary", "nicholas",
        "eric", "stephen", "jonathan", "larry", "justin", "scott", "brandon", "frank",
        "benjamin", "samuel", "patrick", "alexander", "jack", "dennis", "jerry", "henry",
        "aaron", "adam", "nathan", "zachary", "walter", "harold", "kyle", "carl",
        "arthur", "gerald", "roger", "peter", "terry", "sean", "alan", "tom", "tony",
        "mike", "chris", "dave", "nick", "dan", "ben", "sam", "alex", "max", "luke",
        "jake", "noah", "ethan", "oliver", "liam", "mason", "logan", "aiden", "lucas",
        "jackson", "joe", "johnny", "billy", "andy", "brad", "chad", "dean", "derek",
        "evan", "felix", "grant", "harry", "ian", "ivan", "jeff", "jeremy", "joel",
        "julian", "keith", "lance", "leo", "marcus", "mario", "martin", "matt", "neil",
        "omar", "oscar", "owen", "phil", "ralph", "randy", "ray", "rob", "rod",
        "ross", "russell", "travis", "trevor", "victor", "wade", "warren", "wayne",
        // Female
        "mary", "patricia", "linda", "barbara", "elizabeth", "jennifer", "maria",
        "susan", "margaret", "dorothy", "lisa", "nancy", "karen", "betty", "helen",
        "sandra", "donna", "carol", "ruth", "sharon", "michelle", "laura", "sarah",
        "kimberly", "deborah", "jessica", "angela", "melissa", "brenda", "amy", "anna",
        "rebecca", "virginia", "kathleen", "pamela", "martha", "amanda", "stephanie",
        "carolyn", "christine", "marie", "janet", "catherine", "frances", "ann",
        "joyce", "diane", "alice", "julie", "heather", "teresa", "gloria", "evelyn",
        "jean", "cheryl", "katherine", "joan", "ashley", "judith", "rose", "janice",
        "kelly", "nicole", "judy", "christina", "beverly", "denise", "tammy", "irene",
        "jane", "lori", "rachel", "marilyn", "andrea", "kathryn", "louise", "sara",
        "anne", "jacqueline", "wanda", "bonnie", "julia", "ruby", "lois", "tina",
        "emily", "robin", "emma", "olivia", "ava", "isabella", "sophia", "mia",
        "charlotte", "abigail", "harper", "ella", "grace", "lily", "claire", "zoe",
        "victoria", "natalie", "hannah", "julia", "leah", "stella", "eleanor"
    ));

    private final NewsService newsService;
    private volatile List<TrendingTopic> cachedTopics = Collections.emptyList();
    private volatile Instant lastComputedAt = null;

    public TrendingTopicsService(NewsService newsService) {
        this.newsService = newsService;
    }

    /**
     * Per-phrase tracking: which articles contain the phrase, and which of those
     * have it in the title. weight() gives titles an effective ×2 boost since a
     * title hit lands in both sets while a description-only hit lands in one.
     */
    private static final class PhraseStats {
        final Set<Integer> articles = new HashSet<>();
        final Set<Integer> titleArticles = new HashSet<>();

        int weight() { return articles.size() + titleArticles.size(); }

        void absorb(PhraseStats other) {
            articles.addAll(other.articles);
            titleArticles.addAll(other.titleArticles);
        }
    }

    // Polls every 30s: runs immediately once articles are available, then throttles to ~15 min
    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void computeTrendingTopics() {
        List<Article> articles = newsService.getArticles();
        if (articles.isEmpty()) {
            log.info("No articles available yet, will retry");
            return;
        }
        if (lastComputedAt != null &&
                Duration.between(lastComputedAt, Instant.now()).toMinutes() < 14) {
            return;
        }

        int total = articles.size();
        log.info("Computing trending topics from {} articles", total);

        // ── Step 1: Detect proper nouns via mid-sentence capitalisation in descriptions
        // Descriptions are written in sentence-case, so only real proper nouns stay
        // capitalised mid-sentence — unlike Title Case headlines where every word is capital.
        Map<String, int[]> pnStats = new HashMap<>();
        for (Article article : articles) {
            if (article.description() != null && article.description().length() > 30) {
                collectDescriptionStats(article.description(), pnStats);
            }
        }
        Set<String> properNouns = resolveProperNouns(pnStats);
        log.info("Proper-noun candidates ({}): {}…", properNouns.size(),
            properNouns.stream().sorted().limit(40).collect(Collectors.toList()));

        // ── Step 2: Extract n-grams with document frequency ─────────────────────
        // Title and description are tokenised separately so n-grams never span the
        // boundary between them (otherwise titles like "Inside Science: …" would
        // produce phantom trigrams like "science inside science"). Title hits
        // count toward an extra weight so headlines still outrank body copy.
        Map<String, PhraseStats> phraseMap = new HashMap<>();

        for (int idx = 0; idx < articles.size(); idx++) {
            Article article = articles.get(idx);
            extractNgrams(article.title(), idx, true, phraseMap);
            extractNgrams(article.description(), idx, false, phraseMap);
        }

        // ── Step 3: Three-gate filter ────────────────────────────────────────────
        double idfCutoff = total * IDF_MAX_RATIO;

        phraseMap.entrySet().removeIf(e -> {
            String phrase = e.getKey();
            int freq = e.getValue().articles.size();
            String[] words = phrase.split(" ");
            int wc = words.length;

            // Gate A — IDF: phrases in >25 % of all articles are too generic
            if (freq > idfCutoff) return true;

            if (wc == 1) {
                // Gate B — Unigrams must be proper nouns AND not a generic place name.
                // Generic places (Australia, France…) are context, not topics; they can
                // still appear inside bigrams/trigrams so they're not in STOP_WORDS.
                return !properNouns.contains(phrase)
                    || freq < MIN_FREQ_UNIGRAM_PROPER
                    || GENERIC_PLACES.contains(phrase)
                    || FIRST_NAMES.contains(phrase);
            } else if (wc == 2) {
                // Gate C — Bigrams need ≥1 proper noun, or high frequency
                if (freq < MIN_FREQ_BIGRAM_PROPER) return true;
                boolean hasProper = properNouns.contains(words[0]) || properNouns.contains(words[1]);
                return !hasProper && freq < MIN_FREQ_BIGRAM_GENERIC;
            } else {
                // Trigrams are specific enough; just enforce minimum frequency
                return freq < MIN_FREQ_TRIGRAM;
            }
        });

        // ── Step 4: Absorb shorter phrases subsumed by longer ones ───────────────
        Map<String, PhraseStats> absorbed = absorbIntoMultiWord(phraseMap);

        // ── Step 5: Merge phrases that share ≥2 words (e.g. "Gaza Ceasefire Talks"
        //            and "Gaza Ceasefire Deal" → keep the more frequent) ──────────
        Map<String, PhraseStats> merged = mergeByWordOverlap(absorbed);

        // ── Step 6: Deduplicate by proper noun — if the same proper noun drives
        //            multiple topics (e.g. "Tulsi Gabbard" + "Tulsi Hearing"),
        //            keep only the most frequent ──────────────────────────────────
        Map<String, PhraseStats> deduped = deduplicateByProperNoun(merged, properNouns);

        // ── Step 7: Build & sort ──────────────────────────────────────────────────
        List<TrendingTopic> topics = deduped.entrySet().stream()
            .sorted((a, b) -> b.getValue().weight() - a.getValue().weight())
            .limit(MAX_TOPICS)
            .map(e -> {
                String phrase = toTitleCase(e.getKey());
                PhraseStats stats = e.getValue();
                List<Article> topicArticles = stats.articles.stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_ARTICLES_PER_TOPIC)
                    .map(articles::get)
                    .collect(Collectors.toList());
                return new TrendingTopic(phrase, stats.articles.size(), topicArticles);
            })
            .collect(Collectors.toList());

        cachedTopics = Collections.unmodifiableList(topics);
        lastComputedAt = Instant.now();
        log.info("Computed {} trending topics", topics.size());
    }

    private void extractNgrams(String rawText, int idx, boolean fromTitle,
                               Map<String, PhraseStats> phraseMap) {
        if (rawText == null || rawText.isBlank()) return;
        Set<String> seen = new HashSet<>();

        for (List<String> run : tokenizeIntoRuns(rawText)) {
            for (int i = 0; i < run.size(); i++) {
                String t0 = run.get(i);
                if (seen.add(t0)) recordPhrase(phraseMap, t0, idx, fromTitle);

                if (i + 1 < run.size()) {
                    String t1 = run.get(i + 1);
                    String bi = t0 + " " + t1;
                    if (seen.add(bi)) recordPhrase(phraseMap, bi, idx, fromTitle);

                    if (i + 2 < run.size()) {
                        String tri = t0 + " " + t1 + " " + run.get(i + 2);
                        if (seen.add(tri)) recordPhrase(phraseMap, tri, idx, fromTitle);
                    }
                }
            }
        }
    }

    /**
     * Splits text into runs of consecutive content words. Punctuation breaks a
     * run, and so does any word that the n-gram extractor would otherwise skip
     * (stopwords, words shorter than {@link #MIN_WORD_LENGTH}, numbers). This
     * way bigrams and trigrams only span tokens that were actually adjacent in
     * the source — "Pacific and the Ocean" no longer collapses into the bigram
     * "pacific ocean".
     */
    private List<List<String>> tokenizeIntoRuns(String rawText) {
        String normalized = rawText
            .replaceAll("<[^>]*>", " ")
            .replaceAll("['‘’]s", "")
            .toLowerCase();

        // Each clause is the text between punctuation marks; runs only form
        // within a clause, never across one.
        String[] clauses = normalized.split("[^a-z0-9\\s]+");

        List<List<String>> runs = new ArrayList<>();
        for (String clause : clauses) {
            List<String> current = new ArrayList<>();
            for (String w : clause.split("\\s+")) {
                if (w.isEmpty()) continue;
                if (w.length() < MIN_WORD_LENGTH || STOP_WORDS.contains(w) || w.matches("\\d+.*")) {
                    if (!current.isEmpty()) {
                        runs.add(current);
                        current = new ArrayList<>();
                    }
                } else {
                    current.add(w);
                }
            }
            if (!current.isEmpty()) runs.add(current);
        }
        return runs;
    }

    private void recordPhrase(Map<String, PhraseStats> phraseMap, String phrase,
                              int idx, boolean fromTitle) {
        PhraseStats stats = phraseMap.computeIfAbsent(phrase, k -> new PhraseStats());
        stats.articles.add(idx);
        if (fromTitle) stats.titleArticles.add(idx);
    }

    // ── Proper-noun helpers ────────────────────────────────────────────────────

    /**
     * Scans description text for words appearing mid-sentence.
     * In sentence-case prose only proper nouns keep their capital mid-sentence,
     * so capitalisation rate here is a reliable proper-noun signal.
     */
    private void collectDescriptionStats(String description, Map<String, int[]> stats) {
        String text = description
            .replaceAll("<[^>]*>", " ")
            .replaceAll("\\s+", " ")
            .trim();

        // Split on sentence boundaries (keeps sentence-start capitals out of analysis)
        String[] sentences = text.split("(?<=[.!?])\\s+");

        for (String sentence : sentences) {
            String[] words = sentence.split("\\s+");
            for (int i = 1; i < words.length; i++) { // skip position 0 — sentence start
                String raw = words[i].replaceAll("[^a-zA-Z]", "");
                if (raw.length() < MIN_WORD_LENGTH) continue;
                String lower = raw.toLowerCase();
                if (STOP_WORDS.contains(lower)) continue;
                if (lower.matches("\\d+.*")) continue;

                int[] s = stats.computeIfAbsent(lower, k -> new int[2]);
                s[1]++; // total mid-sentence appearances
                if (Character.isUpperCase(raw.charAt(0))) s[0]++; // capitalised mid-sentence
            }
        }
    }

    private Set<String> resolveProperNouns(Map<String, int[]> stats) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int[] s = e.getValue();
            if (s[1] >= PN_MIN_SAMPLES && (double) s[0] / s[1] >= PN_CAP_RATE) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    // ── Phrase helpers ─────────────────────────────────────────────────────────

    private Map<String, PhraseStats> absorbIntoMultiWord(Map<String, PhraseStats> phraseMap) {
        List<String> phrases = new ArrayList<>(phraseMap.keySet());
        Set<String> toAbsorb = new HashSet<>();

        for (int i = 0; i < phrases.size(); i++) {
            String shorter = phrases.get(i);
            String[] sw = shorter.split(" ");
            int shorterFreq = phraseMap.get(shorter).articles.size();

            if (sw.length == 1) {
                // If this unigram appears inside ANY surviving multi-word phrase, absorb it.
                // A standalone first name like "John" or "Tony" should never win over
                // "John Smith" or "Tony Abbott" — even if each combo only appears once.
                boolean hasMultiWordForm = false;
                for (int j = 0; j < phrases.size(); j++) {
                    if (i == j) continue;
                    String[] lw = phrases.get(j).split(" ");
                    if (lw.length > 1 && Arrays.asList(lw).contains(sw[0])) {
                        hasMultiWordForm = true;
                        break;
                    }
                }
                if (hasMultiWordForm) {
                    toAbsorb.add(shorter);
                }
            } else {
                // For multi-word phrases: absorb into a longer phrase if one covers enough
                for (int j = 0; j < phrases.size(); j++) {
                    if (i == j) continue;
                    String longer = phrases.get(j);
                    String[] lw = longer.split(" ");
                    if (lw.length <= sw.length) continue;
                    if (!isSubphrase(sw, lw)) continue;

                    int longerFreq = phraseMap.get(longer).articles.size();
                    if ((double) longerFreq / shorterFreq >= MERGE_THRESHOLD) {
                        toAbsorb.add(shorter);
                        break;
                    }
                }
            }
        }

        Map<String, PhraseStats> result = new HashMap<>(phraseMap);
        toAbsorb.forEach(result::remove);
        return result;
    }

    private Map<String, PhraseStats> deduplicateByProperNoun(
            Map<String, PhraseStats> phraseMap, Set<String> properNouns) {

        // Sort phrases by weight desc — we always keep the most frequent one
        List<String> phrases = phraseMap.entrySet().stream()
            .sorted((a, b) -> b.getValue().weight() - a.getValue().weight())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        Map<String, PhraseStats> result = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();

        for (String phrase : phrases) {
            if (consumed.contains(phrase)) continue;

            PhraseStats combined = new PhraseStats();
            combined.absorb(phraseMap.get(phrase));
            String[] words = phrase.split(" ");
            List<String> members = new ArrayList<>();
            members.add(phrase);

            // For each proper noun in this phrase, absorb any other surviving phrase
            // that also contains that proper noun
            for (String word : words) {
                if (!properNouns.contains(word)) continue;
                for (String other : phrases) {
                    if (other.equals(phrase) || consumed.contains(other)) continue;
                    if (Arrays.asList(other.split(" ")).contains(word)) {
                        combined.absorb(phraseMap.get(other));
                        consumed.add(other);
                        members.add(other);
                    }
                }
            }

            result.put(pickConsolidatedName(members), combined);
        }
        return result;
    }

    private Map<String, PhraseStats> mergeByWordOverlap(Map<String, PhraseStats> phraseMap) {
        // Sort phrases by weight desc so we always keep the more frequent one
        List<String> phrases = phraseMap.entrySet().stream()
            .sorted((a, b) -> b.getValue().weight() - a.getValue().weight())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        Map<String, PhraseStats> result = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();

        for (String p1 : phrases) {
            if (consumed.contains(p1)) continue;
            String[] p1Words = p1.split(" ");

            // Unigrams pass through unchanged — overlap merging only runs between
            // word combinations of length > 1
            if (p1Words.length <= 1) {
                result.put(p1, phraseMap.get(p1));
                continue;
            }

            Set<String> w1 = new HashSet<>(Arrays.asList(p1Words));
            PhraseStats combined = new PhraseStats();
            combined.absorb(phraseMap.get(p1));
            List<String> members = new ArrayList<>();
            members.add(p1);

            for (String p2 : phrases) {
                if (p2.equals(p1) || consumed.contains(p2)) continue;
                String[] p2Words = p2.split(" ");
                if (p2Words.length <= 1) continue;
                Set<String> w2 = new HashSet<>(Arrays.asList(p2Words));
                long shared = w1.stream().filter(w2::contains).count();
                if (shared >= 2) {
                    combined.absorb(phraseMap.get(p2));
                    consumed.add(p2);
                    members.add(p2);
                }
            }

            result.put(pickConsolidatedName(members), combined);
        }
        return result;
    }

    /**
     * When multiple phrases are merged into one consolidated topic, display the
     * shortest member name (by word count) that is at least 2 words long. Falls
     * back to the first member (highest-frequency, due to caller sort order) if
     * no member meets the 2-word floor or no consolidation happened.
     */
    private String pickConsolidatedName(List<String> members) {
        String best = members.get(0);
        if (members.size() == 1) return best;
        int bestLen = Integer.MAX_VALUE;
        for (String m : members) {
            int len = m.split(" ").length;
            if (len >= 2 && len < bestLen) {
                best = m;
                bestLen = len;
            }
        }
        return best;
    }

    private boolean isSubphrase(String[] shorter, String[] longer) {
        outer:
        for (int i = 0; i <= longer.length - shorter.length; i++) {
            for (int j = 0; j < shorter.length; j++) {
                if (!longer[i + j].equals(shorter[j])) continue outer;
            }
            return true;
        }
        return false;
    }

    private String toTitleCase(String phrase) {
        return Arrays.stream(phrase.split(" "))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .collect(Collectors.joining(" "));
    }

    public List<TrendingTopic> getTrendingTopics() {
        return cachedTopics; // sorted by frequency desc; frontend shuffles render order for variety
    }
}
