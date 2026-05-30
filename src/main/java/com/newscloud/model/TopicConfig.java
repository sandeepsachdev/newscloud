package com.newscloud.model;

/**
 * Tunable knobs for the trending-topic pipeline. Held in memory only — changes
 * made through the settings page reset on app restart, which is what we want
 * for tuning experiments.
 */
public class TopicConfig {

    public int maxTopics = 60;
    public int minWordLength = 3;
    public int maxArticlesPerTopic = 20;
    public double mergeThreshold = 0.35;

    public int minFreqUnigramProper = 4;
    public int minFreqBigramProper = 2;
    public int minFreqBigramGeneric = 5;
    public int minFreqTrigram = 2;

    public double idfMaxRatio = 0.25;

    public int pnMinSamples = 2;
    public double pnCapRate = 0.75;

    public int minSources = 2;
    public int windowHours = 24;

    public TopicConfig() {}

    public TopicConfig copy() {
        TopicConfig c = new TopicConfig();
        c.maxTopics = maxTopics;
        c.minWordLength = minWordLength;
        c.maxArticlesPerTopic = maxArticlesPerTopic;
        c.mergeThreshold = mergeThreshold;
        c.minFreqUnigramProper = minFreqUnigramProper;
        c.minFreqBigramProper = minFreqBigramProper;
        c.minFreqBigramGeneric = minFreqBigramGeneric;
        c.minFreqTrigram = minFreqTrigram;
        c.idfMaxRatio = idfMaxRatio;
        c.pnMinSamples = pnMinSamples;
        c.pnCapRate = pnCapRate;
        c.minSources = minSources;
        c.windowHours = windowHours;
        return c;
    }
}
