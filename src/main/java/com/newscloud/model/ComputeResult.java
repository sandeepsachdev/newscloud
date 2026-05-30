package com.newscloud.model;

import java.util.List;

public class ComputeResult {
    public final List<TrendingTopic> topics;
    public final ComputeStats stats;

    public ComputeResult(List<TrendingTopic> topics, ComputeStats stats) {
        this.topics = topics;
        this.stats = stats;
    }
}
