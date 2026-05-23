package com.newscloud.model;

import java.util.List;

public record TrendingTopic(
    String phrase,
    int frequency,
    List<Article> articles
) {}
