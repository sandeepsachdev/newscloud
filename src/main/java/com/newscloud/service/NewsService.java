package com.newscloud.service;

import com.newscloud.model.Article;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private static final List<String> FEED_URLS = List.of(
        // BBC
        "https://feeds.bbci.co.uk/news/world/rss.xml",
        "https://feeds.bbci.co.uk/news/technology/rss.xml",
        "https://feeds.bbci.co.uk/news/business/rss.xml",
        "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
        // US
        "https://feeds.nbcnews.com/nbcnews/public/news",
        "https://www.cbsnews.com/latest/rss/main",
        "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
        // Europe & international
        "https://www.euronews.com/rss?level=theme&name=news",
        "https://www.aljazeera.com/xml/rss/all.xml",
        "https://www.theguardian.com/world/rss",
        "https://www.theguardian.com/technology/rss",
        "https://feeds.npr.org/1001/rss.xml",
        "https://feeds.skynews.com/feeds/rss/world.xml",
        "https://rss.dw.com/xml/rss-en-world",
        "https://www.france24.com/en/rss",
        "https://time.com/feed/",
        "https://feeds.a.dj.com/rss/RSSWorldNews.xml",
        // Australia
        "https://www.abc.net.au/news/feed/51120/rss.xml",
        "https://www.abc.net.au/news/feed/1948/rss.xml",
        "https://www.sbs.com.au/news/feed",
        "https://www.smh.com.au/rss/feed.xml",
        "https://www.theguardian.com/australia-news/rss"
    );

    private final CopyOnWriteArrayList<Article> articles = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>();
    private final AtomicInteger successfulFeeds = new AtomicInteger(0);

    @Scheduled(initialDelay = 0, fixedDelay = 30 * 60_000)
    public void refreshArticles() {
        log.info("Refreshing news articles from {} feeds", FEED_URLS.size());
        List<Article> fresh = new ArrayList<>();
        int success = 0;

        for (String feedUrl : FEED_URLS) {
            try {
                List<Article> fetched = fetchFeed(feedUrl);
                fresh.addAll(fetched);
                success++;
                log.debug("Fetched {} articles from {}", fetched.size(), feedUrl);
            } catch (Exception e) {
                log.warn("Failed to fetch {}: {}", feedUrl, e.getMessage());
            }
        }

        if (!fresh.isEmpty()) {
            List<Article> deduped = dedupeBySourceAndContent(fresh);
            articles.clear();
            articles.addAll(deduped);
            lastUpdated.set(Instant.now());
            successfulFeeds.set(success);
            int dropped = fresh.size() - deduped.size();
            if (dropped > 0) {
                log.info("Loaded {} articles from {}/{} feeds ({} duplicates dropped)",
                        deduped.size(), success, FEED_URLS.size(), dropped);
            } else {
                log.info("Loaded {} articles from {}/{} feeds", deduped.size(), success, FEED_URLS.size());
            }
        }
    }

    // Some feeds (notably BBC) republish the same item under multiple URLs, so
    // a single story can otherwise inflate every topic it mentions. Collapse
    // entries that share the same source + title + description, keeping the
    // first occurrence — feeds list newest-first, so this preserves the
    // earliest-seen URL.
    private static List<Article> dedupeBySourceAndContent(List<Article> input) {
        Set<String> seen = new HashSet<>(input.size() * 2);
        List<Article> out = new ArrayList<>(input.size());
        for (Article a : input) {
            String key = normalize(a.source()) + ""
                       + normalize(a.title()) + ""
                       + normalize(a.description());
            if (seen.add(key)) out.add(a);
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private List<Article> fetchFeed(String feedUrl) throws Exception {
        List<Article> result = new ArrayList<>();

        URL url = new URL(feedUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "NewsCloud/1.0 RSS-Aggregator");
        conn.setInstanceFollowRedirects(true);

        try (XmlReader reader = new XmlReader(conn.getInputStream())) {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(reader);
            String sourceName = feed.getTitle() != null ? feed.getTitle().trim() : feedUrl;

            for (SyndEntry entry : feed.getEntries()) {
                String title = entry.getTitle() != null ? entry.getTitle().trim() : "";
                if (title.isEmpty()) continue;

                String description = "";
                if (entry.getDescription() != null) {
                    description = entry.getDescription().getValue();
                } else if (entry.getContents() != null && !entry.getContents().isEmpty()) {
                    description = entry.getContents().get(0).getValue();
                }
                description = description.replaceAll("<[^>]*>", " ")
                                         .replaceAll("(?i)catch up with the most important stories[^.]*\\.", "")
                                         .replaceAll("\\s+", " ")
                                         .trim();

                String link = entry.getLink() != null ? entry.getLink() : "";
                if (link.isEmpty()) continue;

                Instant published = entry.getPublishedDate() != null
                    ? entry.getPublishedDate().toInstant()
                    : Instant.now();

                result.add(new Article(title, description, link, sourceName, published));
            }
        }

        return result;
    }

    public List<Article> getArticles() {
        return new ArrayList<>(articles);
    }

    public Instant getLastUpdated() {
        return lastUpdated.get();
    }

    public int getSuccessfulFeeds() {
        return successfulFeeds.get();
    }

    public int getTotalFeeds() {
        return FEED_URLS.size();
    }
}
