package com.newscloud.service;

import com.newscloud.model.Article;
import com.newscloud.model.ComputeResult;
import com.newscloud.model.ComputeStats;
import com.newscloud.model.TopicConfig;
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

    // Tuning knobs that used to be hard-coded now live in a mutable TopicConfig
    // owned by this service. The scheduled compute reads them; settings-page
    // edits mutate them; preview runs pass a transient override without
    // touching the active config.

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
        // Geographic features — context, not topics. Still allowed in bigrams/trigrams
        // ("South China Sea", "Indian Ocean", "Amazon River", "Atlas Mountains").
        "sea", "seas", "ocean", "oceans",
        "river", "rivers", "lake", "lakes",
        "mountain", "mountains", "hill", "hills",
        "desert", "deserts", "forest", "forests", "jungle", "jungles",
        "valley", "valleys", "canyon", "canyons", "plateau", "plateaus",
        "beach", "beaches", "coast", "coasts", "shore", "shores",
        "peninsula", "peninsulas", "gulf", "gulfs", "strait", "straits", "channel",
        "harbor", "harbour", "harbors", "harbours",
        "glacier", "glaciers", "volcano", "volcanoes", "delta", "deltas",
        "continent", "continents",
        // Major world cities — typically background context, not the news topic.
        // Hotspot cities (Kyiv, Gaza, Jerusalem, Tehran, Moscow, Damascus, Beirut,
        // Baghdad, Kabul, Washington …) are intentionally omitted so they can
        // surface when genuinely trending. City names stay allowed in bigrams/
        // trigrams ("Paris Agreement", "Tokyo Olympics", "London Bridge").
        "london", "paris", "berlin", "madrid", "rome", "milan", "barcelona",
        "brussels", "amsterdam", "vienna", "prague", "warsaw", "budapest",
        "athens", "lisbon", "dublin", "edinburgh", "glasgow", "manchester",
        "copenhagen", "stockholm", "helsinki", "oslo",
        "istanbul", "ankara",
        "cairo", "johannesburg", "nairobi", "lagos", "casablanca", "addis",
        "beijing", "shanghai", "tokyo", "kyoto", "osaka", "seoul",
        "mumbai", "delhi", "kolkata", "chennai", "bangalore", "hyderabad",
        "karachi", "lahore", "islamabad", "dhaka", "manila", "jakarta",
        "bangkok", "hanoi", "kuala", "lumpur", "taipei",
        "riyadh", "dubai", "doha", "abu",
        "ottawa", "toronto", "montreal", "vancouver",
        "sydney", "melbourne", "auckland", "wellington", "brisbane", "perth",
        "chicago", "boston", "miami", "atlanta", "dallas", "houston",
        "philadelphia", "seattle", "denver", "phoenix",
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
    private volatile ComputeStats cachedStats = null;
    private volatile Instant lastComputedAt = null;
    private volatile TopicConfig activeConfig = new TopicConfig();

    public TrendingTopicsService(NewsService newsService) {
        this.newsService = newsService;
    }

    public List<TrendingTopic> getTrendingTopics() {
        return cachedTopics;
    }

    public ComputeStats getCachedStats() {
        return cachedStats;
    }

    public TopicConfig getActiveConfig() {
        return activeConfig.copy();
    }

    /**
     * Replace the live config and invalidate the scheduling throttle so the
     * next scheduled tick recomputes immediately with the new values. The
     * caller is expected to hand over a fully-populated TopicConfig — partial
     * updates aren't supported (the settings page always submits everything).
     */
    public void setActiveConfig(TopicConfig cfg) {
        this.activeConfig = cfg;
        this.lastComputedAt = null;
    }

    /**
     * Run the pipeline against the given articles + config without touching
     * cached state. Used by the preview endpoint so the settings page can
     * show "before vs after" without disturbing the live word cloud.
     */
    public ComputeResult previewWithConfig(TopicConfig cfg) {
        return compute(newsService.getArticles(), cfg);
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
        List<Article> allArticles = newsService.getArticles();
        if (allArticles.isEmpty()) {
            log.info("No articles available yet, will retry");
            return;
        }
        if (lastComputedAt != null &&
                Duration.between(lastComputedAt, Instant.now()).toMinutes() < 14) {
            return;
        }

        ComputeResult result = compute(allArticles, activeConfig);
        cachedTopics = Collections.unmodifiableList(result.topics);
        cachedStats = result.stats;
        lastComputedAt = Instant.now();

        if (result.topics.isEmpty()) {
            log.info("Computed 0 topics — {} articles total, {} in window",
                    result.stats.totalArticles, result.stats.articlesInWindow);
            return;
        }
        log.info("Computed {} trending topics (highest → lowest):", result.topics.size());
        for (int i = 0; i < result.topics.size(); i++) {
            TrendingTopic t = result.topics.get(i);
            log.info("  {}. {} ({})", i + 1, t.phrase(), t.frequency());
        }
    }

    /**
     * The whole pipeline as a pure(-ish) function: given a snapshot of articles
     * and a config, return the resulting topics plus diagnostic counts at each
     * stage. Doesn't touch cachedTopics / cachedStats — that's the caller's
     * job (the scheduled run does; the preview endpoint doesn't).
     */
    public ComputeResult compute(List<Article> allArticles, TopicConfig cfg) {
        ComputeStats stats = new ComputeStats();
        stats.computedAt = Instant.now();
        stats.totalArticles = allArticles.size();

        // 24h window — articles without a known publish date can't honour
        // the cutoff and are dropped (we can't tell if they're stale).
        Instant cutoff = Instant.now().minus(Duration.ofHours(cfg.windowHours));
        List<Article> articles = allArticles.stream()
                .filter(a -> a.publishedAt() != null && a.publishedAt().isAfter(cutoff))
                .collect(Collectors.toList());
        stats.articlesInWindow = articles.size();
        stats.articlesBySource = articles.stream()
                .filter(a -> a.source() != null)
                .collect(Collectors.groupingBy(Article::source,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        if (articles.isEmpty()) {
            stats.droppedSingleSource = Collections.emptyList();
            return new ComputeResult(Collections.emptyList(), stats);
        }

        int total = articles.size();

        // ── Step 1: Detect proper nouns via mid-sentence capitalisation in descriptions
        Map<String, int[]> pnStats = new HashMap<>();
        for (Article article : articles) {
            if (article.description() != null && article.description().length() > 30) {
                collectDescriptionStats(article.description(), pnStats, cfg);
            }
        }
        Set<String> properNouns = resolveProperNouns(pnStats, cfg);
        stats.properNounCount = properNouns.size();

        // ── Step 2: Extract n-grams ─────────────────────────────────────────────
        Map<String, PhraseStats> phraseMap = new HashMap<>();
        for (int idx = 0; idx < articles.size(); idx++) {
            Article article = articles.get(idx);
            extractNgrams(article.title(), idx, true, phraseMap, cfg);
            extractNgrams(article.description(), idx, false, phraseMap, cfg);
        }
        stats.rawPhrases = phraseMap.size();

        // ── Step 3: Frequency / proper-noun / IDF gates ─────────────────────────
        double idfCutoff = total * cfg.idfMaxRatio;
        phraseMap.entrySet().removeIf(e -> {
            String phrase = e.getKey();
            int freq = e.getValue().articles.size();
            String[] words = phrase.split(" ");
            int wc = words.length;
            if (freq > idfCutoff) return true;
            if (wc == 1) {
                return !properNouns.contains(phrase)
                    || freq < cfg.minFreqUnigramProper
                    || GENERIC_PLACES.contains(phrase)
                    || FIRST_NAMES.contains(phrase);
            } else if (wc == 2) {
                if (freq < cfg.minFreqBigramProper) return true;
                boolean hasProper = properNouns.contains(words[0]) || properNouns.contains(words[1]);
                return !hasProper && freq < cfg.minFreqBigramGeneric;
            } else {
                return freq < cfg.minFreqTrigram;
            }
        });
        stats.afterFrequencyGates = phraseMap.size();

        Map<String, PhraseStats> absorbed = absorbIntoMultiWord(phraseMap, cfg);
        stats.afterAbsorb = absorbed.size();

        Map<String, PhraseStats> merged = mergeByWordOverlap(absorbed);
        stats.afterMerge = merged.size();

        Map<String, PhraseStats> deduped = deduplicateByProperNoun(merged, properNouns);
        stats.afterProperNounDedup = deduped.size();

        // Multi-source gate — keep dropped names for the stats page so the
        // operator can see exactly what got cut as single-outlet noise.
        List<String> dropped = new ArrayList<>();
        Map<String, PhraseStats> multiSource = new LinkedHashMap<>();
        for (Map.Entry<String, PhraseStats> e : deduped.entrySet()) {
            if (countSources(e.getValue(), articles) >= cfg.minSources) {
                multiSource.put(e.getKey(), e.getValue());
            } else {
                dropped.add(toTitleCase(e.getKey()));
            }
        }
        stats.afterMultiSource = multiSource.size();
        stats.droppedSingleSource = dropped.stream().limit(30).collect(Collectors.toList());

        List<TrendingTopic> topics = multiSource.entrySet().stream()
            .sorted((a, b) -> b.getValue().weight() - a.getValue().weight())
            .limit(cfg.maxTopics)
            .map(e -> {
                String phrase = toTitleCase(e.getKey());
                PhraseStats st = e.getValue();
                List<Article> topicArticles = st.articles.stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(cfg.maxArticlesPerTopic)
                    .map(articles::get)
                    .collect(Collectors.toList());
                return new TrendingTopic(phrase, st.articles.size(), topicArticles);
            })
            .collect(Collectors.toList());
        stats.finalTopics = topics.size();

        return new ComputeResult(topics, stats);
    }

    private void extractNgrams(String rawText, int idx, boolean fromTitle,
                               Map<String, PhraseStats> phraseMap, TopicConfig cfg) {
        if (rawText == null || rawText.isBlank()) return;
        Set<String> seen = new HashSet<>();

        for (List<String> run : tokenizeIntoRuns(rawText, cfg)) {
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
    private List<List<String>> tokenizeIntoRuns(String rawText, TopicConfig cfg) {
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
                if (w.length() < cfg.minWordLength || STOP_WORDS.contains(w) || w.matches("\\d+.*")) {
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
    private void collectDescriptionStats(String description, Map<String, int[]> stats, TopicConfig cfg) {
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
                if (raw.length() < cfg.minWordLength) continue;
                String lower = raw.toLowerCase();
                if (STOP_WORDS.contains(lower)) continue;
                if (lower.matches("\\d+.*")) continue;

                int[] s = stats.computeIfAbsent(lower, k -> new int[2]);
                s[1]++; // total mid-sentence appearances
                if (Character.isUpperCase(raw.charAt(0))) s[0]++; // capitalised mid-sentence
            }
        }
    }

    private Set<String> resolveProperNouns(Map<String, int[]> stats, TopicConfig cfg) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int[] s = e.getValue();
            if (s[1] >= cfg.pnMinSamples && (double) s[0] / s[1] >= cfg.pnCapRate) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    // ── Phrase helpers ─────────────────────────────────────────────────────────

    private static int countSources(PhraseStats stats, List<Article> articles) {
        Set<String> sources = new HashSet<>();
        for (int idx : stats.articles) {
            if (idx >= 0 && idx < articles.size()) {
                String src = articles.get(idx).source();
                if (src != null) sources.add(src);
            }
        }
        return sources.size();
    }

    private Map<String, PhraseStats> absorbIntoMultiWord(Map<String, PhraseStats> phraseMap, TopicConfig cfg) {
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
                    if ((double) longerFreq / shorterFreq >= cfg.mergeThreshold) {
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

}
