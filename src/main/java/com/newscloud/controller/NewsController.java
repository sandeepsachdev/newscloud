package com.newscloud.controller;

import com.newscloud.model.ComputeResult;
import com.newscloud.model.ComputeStats;
import com.newscloud.model.TopicConfig;
import com.newscloud.model.TrendingTopic;
import com.newscloud.service.NewsService;
import com.newscloud.service.TrendingTopicsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final TrendingTopicsService trendingTopicsService;
    private final NewsService newsService;
    private final String buildTimestamp;

    public NewsController(TrendingTopicsService trendingTopicsService,
                          NewsService newsService,
                          @Value("${app.build.timestamp:}") String buildTimestamp) {
        this.trendingTopicsService = trendingTopicsService;
        this.newsService = newsService;
        // Maven resource filtering substitutes @maven.build.timestamp@ at package
        // time. If filtering didn't run (e.g. launching from an IDE without a
        // Maven package step) the literal placeholder leaks through — drop it.
        this.buildTimestamp = (buildTimestamp == null || buildTimestamp.isEmpty() || buildTimestamp.startsWith("@"))
                ? null : buildTimestamp;
    }

    @GetMapping("/trending")
    public List<TrendingTopic> getTrending() {
        return trendingTopicsService.getTrendingTopics();
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("articleCount", newsService.getArticles().size());
        status.put("topicCount", trendingTopicsService.getTrendingTopics().size());
        status.put("lastUpdated", newsService.getLastUpdated());
        status.put("buildTimestamp", buildTimestamp);
        status.put("successfulFeeds", newsService.getSuccessfulFeeds());
        status.put("totalFeeds", newsService.getTotalFeeds());
        return status;
    }

    /**
     * Diagnostic counts from the most recent compute run. Powers the stats
     * page — shows where topics are getting dropped (frequency gates, IDF,
     * single-source filter) so the operator can decide what to tune.
     */
    @GetMapping("/stats")
    public ComputeStats getStats() {
        ComputeStats s = trendingTopicsService.getCachedStats();
        return s != null ? s : new ComputeStats();
    }

    @GetMapping("/config")
    public TopicConfig getConfig() {
        return trendingTopicsService.getActiveConfig();
    }

    /** Replace the active config; the next scheduled compute will use it. */
    @PostMapping("/config")
    public TopicConfig setConfig(@RequestBody TopicConfig cfg) {
        trendingTopicsService.setActiveConfig(cfg);
        return trendingTopicsService.getActiveConfig();
    }

    /**
     * Compute the pipeline with the given overrides without committing them
     * to the active config or the cached topic list. Returns both topics and
     * stats so the settings page can render before/after diffs.
     */
    @PostMapping("/preview")
    public ComputeResult preview(@RequestBody TopicConfig cfg) {
        return trendingTopicsService.previewWithConfig(cfg);
    }
}
