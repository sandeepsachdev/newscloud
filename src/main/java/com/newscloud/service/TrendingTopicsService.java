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
        "guardian", "bbc", "cnn", "cbs", "nbc", "npr", "reuters", "skynews", "euronews",
        // Overly generic media/tech terms
        "social", "media", "platform", "platforms", "online", "digital", "tech",
        // Excluded individuals / companies
        "trump", "donald", "google",
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

    private final NewsService newsService;
    private volatile List<TrendingTopic> cachedTopics = Collections.emptyList();
    private volatile Instant lastComputedAt = null;

    public TrendingTopicsService(NewsService newsService) {
        this.newsService = newsService;
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
        Map<String, Set<Integer>> phraseMap = new HashMap<>();

        for (int idx = 0; idx < articles.size(); idx++) {
            Article article = articles.get(idx);
            // Titles are more signal-rich — include them twice
            String text = clean(article.title() + " " + article.title() + " " + article.description());
            List<String> tokens = tokenize(text);
            Set<String> seen = new HashSet<>();

            for (int i = 0; i < tokens.size(); i++) {
                String t0 = tokens.get(i);
                if (seen.add(t0)) phraseMap.computeIfAbsent(t0, k -> new HashSet<>()).add(idx);

                if (i + 1 < tokens.size()) {
                    String t1 = tokens.get(i + 1);
                    String bi = t0 + " " + t1;
                    if (seen.add(bi)) phraseMap.computeIfAbsent(bi, k -> new HashSet<>()).add(idx);

                    if (i + 2 < tokens.size()) {
                        String tri = t0 + " " + t1 + " " + tokens.get(i + 2);
                        if (seen.add(tri)) phraseMap.computeIfAbsent(tri, k -> new HashSet<>()).add(idx);
                    }
                }
            }
        }

        // ── Step 3: Three-gate filter ────────────────────────────────────────────
        double idfCutoff = total * IDF_MAX_RATIO;

        phraseMap.entrySet().removeIf(e -> {
            String phrase = e.getKey();
            int freq = e.getValue().size();
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
                    || GENERIC_PLACES.contains(phrase);
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

        // ── Step 4: Absorb single words subsumed by a multi-word phrase ──────────
        Map<String, Set<Integer>> merged = absorbIntoMultiWord(phraseMap);

        // ── Step 5: Build & sort ──────────────────────────────────────────────────
        List<TrendingTopic> topics = merged.entrySet().stream()
            .sorted((a, b) -> b.getValue().size() - a.getValue().size())
            .limit(MAX_TOPICS)
            .map(e -> {
                String phrase = toTitleCase(e.getKey());
                List<Article> topicArticles = e.getValue().stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_ARTICLES_PER_TOPIC)
                    .map(articles::get)
                    .collect(Collectors.toList());
                return new TrendingTopic(phrase, e.getValue().size(), topicArticles);
            })
            .collect(Collectors.toList());

        cachedTopics = Collections.unmodifiableList(topics);
        lastComputedAt = Instant.now();
        log.info("Computed {} trending topics", topics.size());
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

    private Map<String, Set<Integer>> absorbIntoMultiWord(Map<String, Set<Integer>> phraseMap) {
        List<String> phrases = new ArrayList<>(phraseMap.keySet());
        Set<String> toAbsorb = new HashSet<>();

        for (int i = 0; i < phrases.size(); i++) {
            String shorter = phrases.get(i);
            String[] sw = shorter.split(" ");
            int shorterFreq = phraseMap.get(shorter).size();

            for (int j = 0; j < phrases.size(); j++) {
                if (i == j) continue;
                String longer = phrases.get(j);
                String[] lw = longer.split(" ");
                if (lw.length <= sw.length) continue;
                if (!isSubphrase(sw, lw)) continue;

                int longerFreq = phraseMap.get(longer).size();
                if ((double) longerFreq / shorterFreq >= MERGE_THRESHOLD) {
                    toAbsorb.add(shorter);
                    break;
                }
            }
        }

        Map<String, Set<Integer>> result = new HashMap<>(phraseMap);
        toAbsorb.forEach(result::remove);
        return result;
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

    private String clean(String text) {
        return text
            .replaceAll("<[^>]*>", " ")
            .replaceAll("['‘’]s", "")
            .replaceAll("[^a-zA-Z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase();
    }

    private List<String> tokenize(String text) {
        return Arrays.stream(text.split("\\s+"))
            .filter(w -> w.length() >= MIN_WORD_LENGTH)
            .filter(w -> !STOP_WORDS.contains(w))
            .filter(w -> !w.matches("\\d+.*"))
            .collect(Collectors.toList());
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
