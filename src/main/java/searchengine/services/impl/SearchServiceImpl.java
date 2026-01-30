package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import searchengine.dto.RankDto;
import searchengine.dto.responses.NotOkResponse;
import searchengine.dto.responses.SearchDataResponse;
import searchengine.dto.responses.SearchResponse;
import searchengine.model.*;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.LemmaService;
import searchengine.services.SearchService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexSearchRepository indexRepository;
    private final LemmaService lemmaService;
    private final Status indexSuccessStatus = Status.INDEXED;
    private final double frequencyLimitProportion = 100;


    @Override
    public ResponseEntity<Object> search(String query, String site, Integer offset, Integer limit) throws IOException {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        if (checkIndexStatusNotIndexed(site)) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
        }

        SitePage siteTarget = (site != null && !site.isBlank())
                ? siteRepository.getSitePageByUrl(site)
                : null;

        int countPages = siteTarget != null
                ? pageRepository.getCountPages(siteTarget.getId())
                : pageRepository.getCountPages(null);

        Map<String, Integer> lemmaStringMap = lemmaService.getLemmasFromText(query);

        if (lemmaStringMap.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        List<Lemma> lemmasForSearch = new ArrayList<>();

        for (String lemmaStr : lemmaStringMap.keySet()) {
            List<Lemma> foundLemmas = lemmaRepository.findLemmasByLemmaAndSiteId(
                    lemmaStr,
                    siteTarget != null ? siteTarget.getId() : null
            );

            if (!foundLemmas.isEmpty()) {
                lemmasForSearch.add(foundLemmas.get(0));
            }
        }

        if (lemmasForSearch.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        lemmasForSearch = lemmasForSearch.stream()
                .filter(lemma -> {
                    Integer freq = lemmaRepository.findCountPageByLemma(
                            lemma.getLemma(),
                            siteTarget != null ? siteTarget.getId() : null
                    );

                    if (freq == null || freq == 0) return false;

                    double proportion = (double) freq / countPages;
                    log.debug("Лемма '{}': встречается на {} страницах → {}% (лимит {}%)",
                            lemma.getLemma(), freq, String.format("%.2f", proportion * 100), frequencyLimitProportion);

                    return proportion <= (frequencyLimitProportion / 100.0);
                })
                .sorted(Comparator.comparingInt(Lemma::getFrequency)) // от самых редких к частым
                .collect(Collectors.toList());

        if (lemmasForSearch.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }


        Map<Integer, Double> pageRelevance = new HashMap<>(); // pageId → суммарная релевантность


        Lemma firstLemma = lemmasForSearch.get(0);
        List<IndexSearch> firstIndexes = indexRepository.findIndexesByLemma(firstLemma.getId());

        for (IndexSearch idx : firstIndexes) {
            pageRelevance.put(idx.getPageId(), (double) idx.getLemmaCount());
        }


        for (int i = 1; i < lemmasForSearch.size(); i++) {
            Lemma current = lemmasForSearch.get(i);
            List<IndexSearch> currentIndexes = indexRepository.findIndexesByLemma(current.getId());

            Map<Integer, Double> newRelevance = new HashMap<>();

            for (IndexSearch idx : currentIndexes) {
                Integer pageId = idx.getPageId();
                Double existing = pageRelevance.get(pageId);
                if (existing != null) {
                    newRelevance.put(pageId, existing + idx.getLemmaCount());
                }
            }

            pageRelevance = newRelevance;
            if (pageRelevance.isEmpty()) {
                break;
            }
        }

        if (pageRelevance.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }


        List<Integer> pageIds = new ArrayList<>(pageRelevance.keySet());
        List<Page> pages = pageRepository.findAllById(pageIds);
        Map<Integer, Page> pageMap = pages.stream().collect(Collectors.toMap(Page::getId, p -> p));

        double maxAbs = pageRelevance.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);


        Set<String> targetLemmasSet = lemmasForSearch.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());

        List<SearchDataResponse> results = new ArrayList<>();

        for (Map.Entry<Integer, Double> entry : pageRelevance.entrySet()) {
            int pageId = entry.getKey();
            Page page = pageMap.get(pageId);
            if (page == null) continue;

            SitePage sitePage = siteRepository.findById(page.getSiteId()).orElse(null);
            if (sitePage == null) continue;

            Document doc;
            try {
                doc = Jsoup.parse(page.getContent());
            } catch (Exception e) {
                log.warn("Ошибка парсинга страницы {}: {}", pageId, e.getMessage());
                continue;
            }

            String title = doc.title();
            if (title == null || title.trim().isEmpty()) {
                title = sitePage.getName();
            }


            StringBuilder bestSnippet = new StringBuilder();
            int maxWordsInSnippet = 0;

            Elements paragraphs = doc.select("p, li, div, h1, h2, h3, h4, span, article");
            for (Element el : paragraphs) {
                String text = el.ownText().trim();
                if (text.length() < 40) continue;

                StringBuilder snippet = new StringBuilder(text);
                int foundWords = 0;

                String[] words = text.split("[\\s\\p{Punct}]+");
                for (String word : words) {
                    if (word.isEmpty()) continue;
                    String cleaned = word.replaceAll("\\p{Punct}", "");
                    if (cleaned.isEmpty()) continue;

                    String lemma = lemmaService.getLemmaByWord(cleaned);
                    if (targetLemmasSet.contains(lemma)) {
                        markWord(snippet, word, 0);
                        foundWords++;
                    }
                }

                if (foundWords > maxWordsInSnippet ||
                        (foundWords == maxWordsInSnippet && snippet.length() > bestSnippet.length())) {
                    maxWordsInSnippet = foundWords;
                    bestSnippet = snippet;
                }
            }

            if (maxWordsInSnippet == 0) continue; // нет совпадений — пропускаем

            double relevance = entry.getValue() / maxAbs;

            results.add(new SearchDataResponse(
                    sitePage.getUrl(),
                    sitePage.getName(),
                    page.getPath(),
                    title,
                    bestSnippet.toString(),
                    relevance,
                    maxWordsInSnippet
            ));
        }


        results.sort(Comparator.comparingDouble(SearchDataResponse::getRelevance).reversed()
                .thenComparingInt(SearchDataResponse::getWordsFound).reversed());

        int total = results.size();
        int start = offset * limit;
        int end = Math.min(start + limit, total);

        List<SearchDataResponse> paginated = (start < total)
                ? results.subList(start, end)
                : Collections.emptyList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", true);
        response.put("count", total);
        response.put("data", paginated);

        return ResponseEntity.ok(response);
    }

    private Boolean checkIndexStatusNotIndexed(String site) {
        if (site == null || site.isBlank()) {
            List<SitePage> sites = siteRepository.findAll();
            return sites.stream().anyMatch(s -> !s.getStatus().equals(indexSuccessStatus));
        }
        return !siteRepository.getSitePageByUrl(site).getStatus().equals(indexSuccessStatus);
    }

    private void markWord(StringBuilder textFromElement, String word, int startPosition) {
        int start = textFromElement.indexOf(word, startPosition);
        if (textFromElement.indexOf("<b>", start - 3) == (start - 3)) {
            markWord(textFromElement, word, start + word.length());
            return;
        }
        int end = start + word.length();
        textFromElement.insert(start, "<b>");
        if (end == -1) {
            textFromElement.insert(textFromElement.length(), "</b>");
        } else textFromElement.insert(end + 3, "</b>");
    }
}
