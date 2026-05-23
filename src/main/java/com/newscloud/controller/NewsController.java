package com.newscloud.controller;

import com.newscloud.model.TrendingTopic;
import com.newscloud.service.NewsService;
import com.newscloud.service.TrendingTopicsService;
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

    public NewsController(TrendingTopicsService trendingTopicsService, NewsService newsService) {
        this.trendingTopicsService = trendingTopicsService;
        this.newsService = newsService;
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
        status.put("successfulFeeds", newsService.getSuccessfulFeeds());
        status.put("totalFeeds", newsService.getTotalFeeds());
        return status;
    }
}
