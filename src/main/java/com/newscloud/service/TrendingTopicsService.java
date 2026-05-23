package com.newscloud.service;

import com.newscloud.model.Article;
import com.newscloud.model.TrendingTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrendingTopicsService {

    private static final Logger log = LoggerFactory.getLogger(TrendingTopicsService.class);

    private static final int MAX_TOPICS = 65;
    private static final int MIN_FREQ_UNIGRAM = 3;
    private static final int MIN_FREQ_BIGRAM = 2;
    private static final int MIN_FREQ_TRIGRAM = 2;
    private static final int MIN_WORD_LENGTH = 3;
    private static final double MERGE_THRESHOLD = 0.35;
    private static final int MAX_ARTICLES_PER_TOPIC = 20;

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
        // RSS / web boilerplate
        "read", "reading", "click", "watch", "sign", "share", "subscribe", "follow",
        "latest", "breaking", "update", "updates", "full", "more", "top", "former",
        "continues", "continue", "continued", "learn", "getty", "afp", "reuters",
        // Numbers as words
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "hundred", "thousand", "million", "billion",
        // Days and months
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "june", "july", "august",
        "september", "october", "november", "december"
    ));

    private final NewsService newsService;
    private volatile List<TrendingTopic> cachedTopics = Collections.emptyList();

    public TrendingTopicsService(NewsService newsService) {
        this.newsService = newsService;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15 * 60_000)
    public void computeTrendingTopics() {
        List<Article> articles = newsService.getArticles();
        if (articles.isEmpty()) {
            log.info("No articles available yet, will retry");
            return;
        }

        log.info("Computing trending topics from {} articles", articles.size());

        // phrase -> set of article indices (document frequency)
        Map<String, Set<Integer>> phraseToArticles = new HashMap<>();

        for (int idx = 0; idx < articles.size(); idx++) {
            Article article = articles.get(idx);
            // Weight titles more by prepending them twice
            String text = clean(article.title() + " " + article.title() + " " + article.description());
            List<String> tokens = tokenize(text);

            // Track what we've seen in THIS article to avoid counting duplicates
            Set<String> seenPhrases = new HashSet<>();

            for (int i = 0; i < tokens.size(); i++) {
                String t0 = tokens.get(i);

                if (seenPhrases.add(t0)) {
                    phraseToArticles.computeIfAbsent(t0, k -> new HashSet<>()).add(idx);
                }

                if (i + 1 < tokens.size()) {
                    String t1 = tokens.get(i + 1);
                    String bigram = t0 + " " + t1;
                    if (seenPhrases.add(bigram)) {
                        phraseToArticles.computeIfAbsent(bigram, k -> new HashSet<>()).add(idx);
                    }

                    if (i + 2 < tokens.size()) {
                        String t2 = tokens.get(i + 2);
                        String trigram = t0 + " " + t1 + " " + t2;
                        if (seenPhrases.add(trigram)) {
                            phraseToArticles.computeIfAbsent(trigram, k -> new HashSet<>()).add(idx);
                        }
                    }
                }
            }
        }

        // Filter by minimum document frequency based on phrase length
        phraseToArticles.entrySet().removeIf(e -> {
            int words = e.getKey().split(" ").length;
            int freq = e.getValue().size();
            return (words == 1 && freq < MIN_FREQ_UNIGRAM)
                || (words == 2 && freq < MIN_FREQ_BIGRAM)
                || (words >= 3 && freq < MIN_FREQ_TRIGRAM);
        });

        // Absorb single words that are well-represented by a multi-word phrase
        Map<String, Set<Integer>> merged = absorbIntoMultiWord(phraseToArticles);

        // Build final topic list sorted by frequency
        List<TrendingTopic> topics = merged.entrySet().stream()
            .sorted((a, b) -> b.getValue().size() - a.getValue().size())
            .limit(MAX_TOPICS)
            .map(e -> {
                String phrase = toTitleCase(e.getKey());
                Set<Integer> articleIndices = e.getValue();
                List<Article> topicArticles = articleIndices.stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_ARTICLES_PER_TOPIC)
                    .map(articles::get)
                    .collect(Collectors.toList());
                return new TrendingTopic(phrase, articleIndices.size(), topicArticles);
            })
            .collect(Collectors.toList());

        cachedTopics = Collections.unmodifiableList(topics);
        log.info("Computed {} trending topics", topics.size());
    }

    private Map<String, Set<Integer>> absorbIntoMultiWord(Map<String, Set<Integer>> phraseMap) {
        Set<String> multiWord = phraseMap.keySet().stream()
            .filter(p -> p.contains(" "))
            .collect(Collectors.toSet());

        Set<String> toAbsorb = new HashSet<>();

        for (String unigram : new ArrayList<>(phraseMap.keySet())) {
            if (unigram.contains(" ")) continue;
            int uniFreq = phraseMap.get(unigram).size();

            for (String multi : multiWord) {
                String[] parts = multi.split(" ");
                boolean contains = false;
                for (String part : parts) {
                    if (part.equals(unigram)) {
                        contains = true;
                        break;
                    }
                }
                if (contains) {
                    int multiFreq = phraseMap.get(multi).size();
                    if ((double) multiFreq / uniFreq >= MERGE_THRESHOLD) {
                        toAbsorb.add(unigram);
                        break;
                    }
                }
            }
        }

        Map<String, Set<Integer>> result = new HashMap<>(phraseMap);
        toAbsorb.forEach(result::remove);
        return result;
    }

    private String clean(String text) {
        return text
            .replaceAll("<[^>]*>", " ")
            .replaceAll("'s|'s", "")
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
        return cachedTopics;
    }
}
