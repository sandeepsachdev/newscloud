package com.newscloud.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pipeline diagnostics for one compute run. The counts let the stats page
 * visualise where topics are getting dropped, so the operator can tell
 * whether (say) the IDF gate or the multi-source gate is doing the most
 * filtering on a given news cycle.
 */
public class ComputeStats {
    public Instant computedAt;
    public int totalArticles;
    public int articlesInWindow;
    public int properNounCount;

    // Phrase counts at each pipeline stage (after that stage runs).
    public int rawPhrases;
    public int afterFrequencyGates;
    public int afterAbsorb;
    public int afterMerge;
    public int afterProperNounDedup;
    public int afterMultiSource;
    public int finalTopics;

    // Number of articles each source contributed to the in-window pool.
    public Map<String, Integer> articlesBySource;

    // Topics dropped at the multi-source gate (single-outlet noise) — useful
    // for spotting whether a feed is over-represented.
    public List<String> droppedSingleSource;
}
