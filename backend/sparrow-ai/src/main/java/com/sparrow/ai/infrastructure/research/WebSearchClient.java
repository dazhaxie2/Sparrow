package com.sparrow.ai.infrastructure.research;

import com.sparrow.ai.infrastructure.config.AiProperties;
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

@Component
public class WebSearchClient {

    public record SearchSource(String sourceRef, String title, String url, String publisher, String snippet) {
    }

    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "Chrome/124.0 Mobile Safari/537.36 SparrowResearch/1.0";
    private final AiProperties props;

    public WebSearchClient(AiProperties props) {
        this.props = props;
    }

    public List<SearchSource> search(String title, String brief) {
        List<String> queries = List.of(
                title + " 产业链 上游 供应商 原材料",
                title + " 产业链 中游 制造 代工 核心企业",
                title + " 下游 客户 应用 市场",
                title + " 竞争格局 市场份额 行业报告",
                title + " 供应链 风险 政策 技术趋势 " + compact(brief, 80));

        Map<String, Candidate> deduplicated = new LinkedHashMap<>();
        for (String query : queries) {
            for (Candidate candidate : searchOne(query)) {
                deduplicated.putIfAbsent(candidate.url(), candidate);
                if (deduplicated.size() >= 15) break;
            }
            if (deduplicated.size() >= 15) break;
        }

        List<SearchSource> sources = new ArrayList<>();
        int index = 1;
        for (Candidate candidate : deduplicated.values()) {
            Candidate enriched = enrich(candidate);
            sources.add(new SearchSource("S" + index++, compact(enriched.title(), 300),
                    compact(enriched.url(), 1200), compact(enriched.publisher(), 160),
                    compact(enriched.snippet(), 1200)));
        }
        return sources;
    }

    private List<Candidate> searchOne(String query) {
        List<Candidate> result = new ArrayList<>();
        try {
            String base = props.chainResearchSearchUrl();
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
                String text = clean(block.text());
                String linkTitle = clean(link.text());
                if (!isPublicHttpUrl(href) || text.length() < 35) continue;
                result.add(new Candidate(linkTitle.isBlank() ? compact(text, 100) : linkTitle,
                        href, hostOf(href), compact(text, 850)));
                if (result.size() >= 4) break;
            }

            // 搜索站点结构调整时仍保留联网证据，而不是悄悄退化成模型常识。
            if (result.isEmpty()) {
                String text = clean(document.body() == null ? "" : document.body().text());
                if (!text.isBlank()) {
                    result.add(new Candidate(query + " - 联网检索", url, hostOf(url), compact(text, 850)));
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
            String title = clean(document.title());
            String body = clean(document.body() == null ? "" : document.body().text());
            return new Candidate(title.isBlank() ? source.title() : title, finalUrl,
                    hostOf(finalUrl), body.length() < 80 ? source.snippet() : compact(body, 900));
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

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String compact(String value, int max) {
        String clean = clean(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record Candidate(String title, String url, String publisher, String snippet) {
    }
}
