package com.newscloud.model;

import java.time.Instant;

public record Article(
    String title,
    String description,
    String url,
    String source,
    Instant publishedAt
) {}
