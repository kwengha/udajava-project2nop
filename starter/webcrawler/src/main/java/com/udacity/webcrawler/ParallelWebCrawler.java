package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final int maxDepth;
  private final List<Pattern> ignoredUrls;
  private final PageParserFactory parserFactory;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @TargetParallelism int threadCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls,
          PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> counts = new HashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();

    startingUrls.stream().map(startingUrl -> new CrawlTask(clock, startingUrl, deadline, maxDepth, counts, visitedUrls, parserFactory, ignoredUrls)).forEach(pool::invoke);

    return counts.isEmpty() ? new CrawlResult.Builder()
            .setWordCounts(counts)
            .setUrlsVisited(visitedUrls.size())
            .build() : new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();

  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  static final class CrawlTask extends RecursiveAction {
    private final Clock clock;
    private final String url;
    private final Instant deadline;
    private final int maxDepth;
    private final Map<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrls;
    private final PageParserFactory parserFactory;
    private final List<Pattern> ignoredUrls;

    CrawlTask(Clock clock,
              String url,
              Instant deadline,
              int maxDepth,
              Map<String, Integer> counts,
              ConcurrentSkipListSet<String> visitedUrls,
              PageParserFactory parserFactory,
              List<Pattern> ignoredUrls) {
      this.clock = clock;
      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;
      this.parserFactory = parserFactory;
      this.ignoredUrls = ignoredUrls;
    }

    @Override
    protected void compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) return;
      if (ignoredUrls.stream().anyMatch(pattern -> pattern.matcher(url).matches())) return;
      if (!visitedUrls.add(url)) {
        return;
      }
      PageParser.Result result = parserFactory.get(url).parse();
      result.getWordCounts().forEach((key, value) -> {
        counts.put(key, counts.containsKey(key) ? Integer.valueOf(value + counts.get(key)) : value);
      });
      List<CrawlTask> tasks = result.getLinks()
              .stream()
              .map(link -> new CrawlTask(clock, link, deadline, maxDepth - 1, counts, visitedUrls, parserFactory, ignoredUrls))
              .collect(Collectors.toList());
      invokeAll(tasks);
    }
  }
}
