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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private static final List<String> FEED_URLS = List.of(
        "https://feeds.bbci.co.uk/news/world/rss.xml",
        "https://feeds.bbci.co.uk/news/technology/rss.xml",
        "https://feeds.bbci.co.uk/news/business/rss.xml",
        "https://feeds.apnews.com/apnews/topnews",          // replaced reuters/topNews
        "https://feeds.apnews.com/apnews/politics",         // replaced reuters/worldNews
        "https://feeds.apnews.com/apnews/science",
        "https://www.aljazeera.com/xml/rss/all.xml",
        "https://www.theguardian.com/world/rss",
        "https://www.theguardian.com/technology/rss",
        "https://feeds.npr.org/1001/rss.xml",
        "https://feeds.skynews.com/feeds/rss/world.xml",
        "https://rss.dw.com/xml/rss-en-world",
        "https://www.france24.com/en/rss",
        "https://www.abc.net.au/news/feed/51120/rss.xml",
        "https://feeds.a.dj.com/rss/RSSWorldNews.xml"
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
            articles.clear();
            articles.addAll(fresh);
            lastUpdated.set(Instant.now());
            successfulFeeds.set(success);
            log.info("Loaded {} articles from {}/{} feeds", fresh.size(), success, FEED_URLS.size());
        }
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
