package com.sparrow.industrychain.infrastructure.llm;

import com.sparrow.common.ai.Texts;
import com.sparrow.industrychain.infrastructure.config.IndustryChainProperties;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class WebSearchClient {

    public record SearchSource(String sourceRef, String title, String url, String publisher, String snippet) {
    }

    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "Chrome/124.0 Mobile Safari/537.36 SparrowResearch/1.0";

    /**
     * 联网检索/富化专用的有界线程池。这些是阻塞 IO(搜狗搜索页抓取 + 各来源页正文下载),
     * 不应占用调研编排与 Agent 执行器(industryChainRunExecutor / industryChainAgentExecutor)。
     * 限并发以兼顾速度与搜索引擎反爬:同一 host 瞬时并发过高易被限流。
     */
    private static final int SEARCH_CONCURRENCY = 3;
    private static final ExecutorService IO_EXECUTOR =
            Executors.newFixedThreadPool(SEARCH_CONCURRENCY, r -> {
                Thread t = new Thread(r, "industry-chain-websearch-");
                t.setDaemon(true);
                return t;
            });

    private final IndustryChainProperties props;

    public WebSearchClient(IndustryChainProperties props) {
        this.props = props;
    }

    public List<SearchSource> search(String title, String brief) {
        return search(title, brief, List.of(), 0);
    }

    /**
     * 联网检索并富化来源。
     *
     * @param startRefIndex 起始编号偏移：已存在 N 条来源（如用户附件）时，
     *                     本方法的搜索结果从 S(N+1) 开始编号，避免与附件冲突。
     * @param extraQueries 规划 Agent 产出的补充查询词；为空时仅用默认中文产业链模板。
     */
    public List<SearchSource> search(String title, String brief, List<String> extraQueries, int startRefIndex) {
        List<String> queries = new ArrayList<>(List.of(
                title + " 产业链 上游 供应商 原材料",
                title + " 产业链 中游 制造 代工 核心企业",
                title + " 下游 客户 应用 市场",
                title + " 竞争格局 市场份额 行业报告",
                title + " 供应链 风险 政策 技术趋势 " + Texts.compact(brief, 80)));
        if (extraQueries != null) {
            for (String query : extraQueries) {
                String trimmed = query == null ? "" : query.trim();
                if (!trimmed.isBlank() && !queries.contains(trimmed)) queries.add(trimmed);
            }
        }

        // 多查询并行检索(限并发 SEARCH_CONCURRENCY 路,避免触发搜索引擎反爬限流),
        // 去重后再并行富化各候选来源,把「串行 N×(检索+富化)」压缩为接近单次往返。
        List<CompletableFuture<List<Candidate>>> searchFutures = new ArrayList<>();
        for (String query : queries) {
            searchFutures.add(CompletableFuture.supplyAsync(() -> searchOne(query), IO_EXECUTOR));
        }
        Map<String, Candidate> deduplicated = new LinkedHashMap<>();
        int remaining = 15;
        for (CompletableFuture<List<Candidate>> future : searchFutures) {
            List<Candidate> candidates;
            try {
                candidates = future.join();
            } catch (Exception ignored) {
                continue;
            }
            for (Candidate candidate : candidates) {
                deduplicated.putIfAbsent(candidate.url(), candidate);
                if (--remaining <= 0) break;
            }
            if (remaining <= 0) break;
        }

        // 并行富化(同样限并发),失败的单条回退到未富化候选(见 enrich 内部 catch)。
        List<Candidate> toEnrich = new ArrayList<>(deduplicated.values());
        List<CompletableFuture<Candidate>> enrichFutures = toEnrich.stream()
                .map(candidate -> CompletableFuture.supplyAsync(() -> enrich(candidate), IO_EXECUTOR))
                .toList();

        List<SearchSource> sources = new ArrayList<>();
        int index = startRefIndex + 1;
        for (CompletableFuture<Candidate> future : enrichFutures) {
            Candidate enriched;
            try {
                enriched = future.join();
            } catch (Exception ignored) {
                continue;
            }
            sources.add(new SearchSource("S" + index++, Texts.compact(enriched.title(), 300),
                    Texts.compact(enriched.url(), 1200), Texts.compact(enriched.publisher(), 160),
                    Texts.compact(enriched.snippet(), 1200)));
        }
        return sources;
    }

    private List<Candidate> searchOne(String query) {
        List<Candidate> result = new ArrayList<>();
        try {
            String base = props.searchUrl();
            String separator = base.contains("?") ? "&" : "?";
            String url = base + separator + "keyword=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            Document document = Jsoup.connect(url)
                    .userAgent(MOBILE_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(20_000)
                    .followRedirects(true)
                    .get();

            for (Element block : document.select(".vrResult, .result, .results, .vrwrap")) {
                Element link = block.selectFirst("h3 a[href], .vr-title a[href], a[href]");
                if (link == null) continue;
                String href = absoluteUrl(link);
                String text = Texts.collapse(block.text());
                String linkTitle = Texts.collapse(link.text());
                if (!isPublicHttpUrl(href) || text.length() < 35) continue;
                result.add(new Candidate(linkTitle.isBlank() ? Texts.compact(text, 100) : linkTitle,
                        href, hostOf(href), Texts.compact(text, 850)));
                if (result.size() >= 4) break;
            }

            // 搜索站点结构调整时仍保留联网证据，而不是悄悄退化成模型常识。
            if (result.isEmpty()) {
                String text = Texts.collapse(document.body() == null ? "" : document.body().text());
                if (!text.isBlank()) {
                    result.add(new Candidate(query + " - 联网检索", url, hostOf(url), Texts.compact(text, 850)));
                }
            }
        } catch (Exception ignored) {
            // 单条检索失败由其余查询补足；全部失败时由编排器显式终止任务。
        }
        return result;
    }

    private Candidate enrich(Candidate source) {
        if (!isPublicHttpUrl(source.url()) || source.url().contains("sogou.com")) return source;
        try {
            Connection.Response response = Jsoup.connect(source.url())
                    .userAgent(MOBILE_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(12_000)
                    .maxBodySize(1_500_000)
                    .followRedirects(true)
                    .execute();
            String finalUrl = response.url().toString();
            if (!isPublicHttpUrl(finalUrl)) return source;
            Document document = response.parse();
            String title = Texts.collapse(document.title());
            String body = Texts.collapse(document.body() == null ? "" : document.body().text());
            return new Candidate(title.isBlank() ? source.title() : title, finalUrl,
                    hostOf(finalUrl), body.length() < 80 ? source.snippet() : Texts.compact(body, 900));
        } catch (Exception ignored) {
            return source;
        }
    }

    private String absoluteUrl(Element link) {
        String absolute = link.absUrl("href");
        return absolute.isBlank() ? link.attr("href") : absolute;
    }

    private boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            String lower = host.toLowerCase(Locale.ROOT);
            return !lower.equals("localhost") && !lower.endsWith(".local")
                    && !lower.startsWith("127.") && !lower.startsWith("10.")
                    && !lower.startsWith("192.168.") && !lower.startsWith("169.254.")
                    && !lower.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
        } catch (Exception error) {
            return false;
        }
    }

    private String hostOf(String value) {
        try {
            String host = URI.create(value).getHost();
            return host == null ? "互联网来源" : host;
        } catch (Exception ignored) {
            return "互联网来源";
        }
    }

    private record Candidate(String title, String url, String publisher, String snippet) {
    }
}


