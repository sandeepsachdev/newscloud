package com.newscloud.controller;

import com.newscloud.model.TrendingTopic;
import com.newscloud.service.NewsService;
import com.newscloud.service.TrendingTopicsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
