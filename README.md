# NewsCloud

A Spring Boot application that aggregates world news from multiple RSS feeds, extracts trending topics via NLP, and displays them as an interactive word cloud.

---

## Features

- Fetches 22 global RSS feeds (BBC, NBC, CBS, NY Times, Al Jazeera, The Guardian, NPR, Sky News, DW, France 24, Time, WSJ, ABC Australia, SBS, SMH, Guardian Australia, Euronews)
- Extracts trending topics using n-gram frequency analysis with proper noun detection
- Word cloud cycles through 4 shapes every 20 seconds (Circle → Square → Rectangle → Ellipse)
- Layout shuffled on every render for visual variety
- Click any word to see a modal with articles sourced from that topic
- Click any article to open it in a new tab
- Mobile-responsive dark UI
- Single-command Docker deploy

---

## Prompts Used to Build This Project

### 1 — Initial build

```
create a spring boot app which takes news articles from many sources are the world and displays a word cloud with trending topics from these news articles. Exclude common words from the trending topics. Trending topics can be a single word or multiple words. Combine where a single word is part of trending topic which is multiple words. Make it render well on mobile and easy to deploy on render using a dockerfile. The word cloud should rearrange itself into a different shape every 20 seconds. The user should be able to select a trending word and see the pop out dialog with a list of articles which this trending topic was sourced on and should then be able to drill in on individual articles to be taken to them in a new window
```

### 2 — Model switch

```
change model
```

### 3 — Topic quality improvement

```
the trending topic selection is not working very well. What do you suggest
```

*(Accepted the recommendation: proper noun detection via mid-sentence capitalisation, removal of generic-unigram path, IDF filter, expanded stop words.)*

### 4 — Permission prompt reduction

```
/fewer-permission-prompts
```

### 5 — README update

```
update readme with all prompts
```

### 6 — Shape changes

```
remove heart and pentagram and diamond shapes and add square and rectangle
```

### 7 — Feed fixes (Reuters/CNN)

```
Failed to fetch https://feeds.reuters.com/reuters/topNews [...] Failed to fetch https://rss.cnn.com/rss/edition.rss
```

### 8 — Country name filtering

```
too many of the worlds are just country names
```

### 9 — Feed fixes (AP News)

```
Failed to fetch https://feeds.apnews.com/apnews/topnews [...]
```

### 10 — Remove star shape, add ellipse

```
remove star
add ellipse
```

### 11 — Euronews boilerplate

```
euronews has this on every articles and it is skewing the topics "Catch up with..."
```

### 12 — Exclude Trump/Donald

```
don't have any topics with trump as a keyword
don't include the word donald
```

### 13 — Subset topic merging

```
topics that are a subset of each other should be combined
```

### 14 — More news sources

```
add more news sources especially australian ones
```

### 15 — Refresh variety

```
on refreshing don't always show the same topics. mix it up a bit
```

### 16 — Crowded word cloud fix

```
something wrong with the refreshing. sometimes shows 6 words only and others times many more
word cloud looks a bit crowded now
this is good for mobile previous is good for desktop
```

### 17 — Word overlap merging

```
where two out of the three words in a topic are the same treat that as the same one and show the most frequent to represent all
```

### 18 — Topic quality tuning

```
i still don't think the trending topics are that good. lots of generics words and just country names and random words like park
not generating enough topics now
social media is probably appearing too often
don't include abc / don't allow the word pro to be a trending topic
```

### 19 — First name filtering

```
only the first word of names is being shown. only showing Tony rather than "tony abbott"
john still appearing. Tom still appearing instead of tom hanks. Perhaps exclude common first names by themselves only count them in anagrams
```

### 20 — First load rendering fix

```
text is not appearing properly until browser is manually refreshed once
```

---

## Running Locally

```bash
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080). News loads in ~15 seconds on first start, topics appear within 30 seconds.

---

## Deploy to Render

1. Push the repo to GitHub
2. On Render → **New Web Service** → connect repo → **Environment: Docker**
3. No extra config needed — Render's `PORT` env var is read automatically

---

## Project Structure

```
src/main/java/com/newscloud/
├── NewsCloudApplication.java          # @SpringBootApplication + @EnableScheduling
├── controller/
│   └── NewsController.java            # GET /api/trending, GET /api/status
├── model/
│   ├── Article.java                   # record: title, description, url, source, publishedAt
│   └── TrendingTopic.java             # record: phrase, frequency, articles
└── service/
    ├── NewsService.java               # RSS fetcher — refreshes every 30 min
    └── TrendingTopicsService.java     # N-gram extractor — recomputes every 15 min

src/main/resources/static/index.html   # wordcloud2.js + Bootstrap 5 frontend
Dockerfile                             # multi-stage Maven → JRE build
```

---

## Trending Topic Algorithm

1. **RSS fetch** — 22 feeds polled every 30 minutes via Rome library
2. **N-gram extraction** — unigrams, bigrams, trigrams scored by document frequency (article titles weighted 2×)
3. **Proper noun filter** — unigrams only qualify if capitalised mid-sentence in descriptions at ≥ 75% rate with ≥ 2 samples
4. **First name filter** — ~200 common first names blocked as standalone topics (still appear in bigrams like "Tom Hanks")
5. **Generic places filter** — countries, regions, structural words (park, court, war…) blocked as unigrams
6. **IDF cutoff** — phrases present in > 25% of all articles are too generic and removed
7. **Subphrase merge** — shorter phrases that are consecutive subsets of longer ones are absorbed
8. **Word-overlap merge** — phrases sharing ≥ 2 words are collapsed into the most frequent representative
9. **Stop words** — 200+ words covering articles, prepositions, auxiliaries, common news verbs, source names, and RSS boilerplate

### Tuning knobs (`TrendingTopicsService.java`)

| Constant | Default | Effect |
|----------|---------|--------|
| `MIN_FREQ_UNIGRAM_PROPER` | 4 | Min articles a proper-noun unigram must appear in |
| `MIN_FREQ_BIGRAM_PROPER` | 2 | Min articles for a proper-noun bigram |
| `MIN_FREQ_BIGRAM_GENERIC` | 5 | Min articles for a bigram with no proper noun |
| `MIN_FREQ_TRIGRAM` | 2 | Min articles for a trigram |
| `IDF_MAX_RATIO` | 0.25 | Fraction of all articles above which a phrase is filtered |
| `PN_CAP_RATE` | 0.75 | Min mid-sentence capitalisation rate to classify as proper noun |
| `PN_MIN_SAMPLES` | 2 | Min mid-sentence appearances before classifying as proper noun |
| `MERGE_THRESHOLD` | 0.35 | Overlap ratio to absorb a shorter phrase into a longer one |
| `MAX_TOPICS` | 60 | Topic pool size |
