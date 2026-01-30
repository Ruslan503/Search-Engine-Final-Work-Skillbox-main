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

//    @Override
//    public ResponseEntity<Object> search(String query, String site, Integer offset, Integer limit) throws IOException {
//        if (checkIndexStatusNotIndexed(site)) {
//            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
//        }
//        //
//        SitePage siteTarget = siteRepository.getSitePageByUrl(site);
//        Integer countPages = siteTarget != null ? pageRepository.getCountPages(siteTarget.getId()) : pageRepository.getCountPages(null);
//
//        //Exclusion lemmas by frequent
//        List<Lemma> lemmasForSearch = lemmaService.getLemmasFromText(query).keySet().stream().map(
//                        it -> lemmaRepository.findLemmasByLemmaAndSiteId(it, siteTarget != null ? siteTarget.getId() : null))
//                .flatMap(Collection::stream).collect(Collectors.toList());
//        lemmasForSearch.removeIf(e -> {
//            Integer lemmaFrequency = lemmaRepository.findCountPageByLemma(e.getLemma(), e.getSiteId());
//            if (lemmaFrequency == null) return true;
//            log.info("Lemma frequency(" + e + "):" + lemmaFrequency);
//            log.info("Frequency limit:" + (double) lemmaFrequency / countPages);
//            return ((double) lemmaFrequency / countPages > frequencyLimitProportion);
//        });
//
//        if (lemmasForSearch.isEmpty()) {
//            return ResponseEntity.ok(Collections.emptyList());
//        }
//
//        //Sorting lemmas by frequent
//        List<Lemma> sortedLemmasToSearch = lemmasForSearch.stream().
//                map(l -> new AbstractMap.SimpleEntry<>(l.getFrequency(), l)).
//                sorted(Comparator.comparingInt(Map.Entry::getKey)).
//                map(Map.Entry::getValue).toList();
//
//
//        //Search pages by first lemma
//        Map<Integer, IndexSearch> indexesByLemmas = indexRepository.findIndexesByLemma(sortedLemmasToSearch.get(0).getId()).stream().collect(Collectors.toMap(IndexSearch::getPageId, index -> index));
//        for (int i = 1; i <= sortedLemmasToSearch.size() - 1; i++) {
//            List<IndexSearch> indexNextLemma = indexRepository.findIndexesByLemma(sortedLemmasToSearch.get(i).getId());
//            List<Integer> pagesToSave = new ArrayList<>();
//            for (IndexSearch indexNext : indexNextLemma) {
//                if (indexesByLemmas.containsKey(indexNext.getPageId())) {
//                    pagesToSave.add(indexNext.getPageId());
//                }
//            }
//            indexesByLemmas.entrySet().removeIf(entry -> !pagesToSave.contains(entry.getKey()));
//        }
//
//        //Output if empty result
//        if (indexesByLemmas.isEmpty()) {
//            return ResponseEntity.ok().body(new SearchResponse(true, 0, Collections.emptyList()));
//        }
//
//        //Rank calculation
//        Set<RankDto> pagesRelevance = new HashSet<>();
//        int pageId = indexesByLemmas.values().stream().toList().get(0).getPageId();
//        RankDto rankPage = new RankDto();
//        for (IndexSearch index : indexesByLemmas.values()) {
//            if (index.getPageId() == pageId) {
//                rankPage.setPage(index.getPage());
//            } else {
//                rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
//                pagesRelevance.add(rankPage);
//                rankPage = new RankDto();
//                rankPage.setPage(index.getPage());
//                pageId = index.getPageId();
//            }
//            rankPage.setPageId(index.getPageId());
//            rankPage.setAbsRelevance(rankPage.getAbsRelevance() + index.getLemmaCount());
//            if (rankPage.getMaxLemmaRank() < index.getLemmaCount()) rankPage.setMaxLemmaRank(index.getLemmaCount());
//        }
//        rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
//        pagesRelevance.add(rankPage);
//
//        //Sort pages Relevance
//        List<RankDto> pagesRelevanceSorted = pagesRelevance.stream().sorted(Comparator.comparingDouble(RankDto::getRelativeRelevance).reversed()).toList();
//
//        //Converting pages relevance to searchDataResponses
//        List<String> simpleLemmasFromSearch = new ArrayList<>(lemmasForSearch.stream().map(Lemma::getLemma).toList());
//        List<SearchDataResponse> searchDataResponses = new ArrayList<>();
//        for (RankDto rank : pagesRelevanceSorted) {
//            Document doc = Jsoup.parse(rank.getPage().getContent());
//            List<String> sentences = doc.body().getElementsMatchingOwnText("[\\p{IsCyrillic}]").stream().map(Element::text).toList();
//            for (String sentence : sentences) {
//                StringBuilder textFromElement = new StringBuilder(sentence);
//                List<String> words = List.of(sentence.split("[\s:punct]"));
//                int searchWords = 0;
//                for (String word : words) {
//                    String lemmaFromWord = lemmaService.getLemmaByWord(word.replaceAll("\\p{Punct}", ""));
//                    if (simpleLemmasFromSearch.contains(lemmaFromWord)) {
//                        markWord(textFromElement, word, 0);
//                        searchWords += 1;
//                    }
//                }
//                if (searchWords != 0) {
//                    SitePage sitePage = siteRepository.findById(pageRepository.findById(rank.getPageId()).get().getSiteId()).get();
//                    searchDataResponses.add(new SearchDataResponse(
//                            sitePage.getUrl(),
//                            sitePage.getName(),
//                            rank.getPage().getPath(),
//                            doc.title(),
//                            textFromElement.toString(),
//                            rank.getRelativeRelevance(),
//                            searchWords
//                    ));
//                }
//            }
//        }
//        List<SearchDataResponse> sortedSearchDataResponse = searchDataResponses.stream().sorted(Comparator.comparingDouble(SearchDataResponse::getRelevance).reversed()).toList();
//        List<SearchDataResponse> result = new ArrayList<>();
//        for (int i = limit * offset; i <= limit * offset + limit; i++) {
//            try {
//                result.add(sortedSearchDataResponse.get(i));
//            } catch (IndexOutOfBoundsException ex) {
//                break;
//            }
//        }
//        result = result.stream().sorted(Comparator.comparingInt(SearchDataResponse::getWordsFound).reversed()).toList();
//        return ResponseEntity.ok(result);
//    }

    @Override
    public ResponseEntity<Object> search(String query, String site, Integer offset, Integer limit) throws IOException {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        if (checkIndexStatusNotIndexed(site)) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
        }

        // Определяем целевой сайт (если указан)
        SitePage siteTarget = (site != null && !site.isBlank())
                ? siteRepository.getSitePageByUrl(site)
                : null;

        int countPages = siteTarget != null
                ? pageRepository.getCountPages(siteTarget.getId())
                : pageRepository.getCountPages(null);

        // 1. Получаем леммы из текста запроса (Map<лемма_строка, частота_в_запросе>)
        Map<String, Integer> lemmaStringMap = lemmaService.getLemmasFromText(query);

        if (lemmaStringMap.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        // 2. Находим реальные сущности Lemma по строкам
        List<Lemma> lemmasForSearch = new ArrayList<>();

        for (String lemmaStr : lemmaStringMap.keySet()) {
            List<Lemma> foundLemmas = lemmaRepository.findLemmasByLemmaAndSiteId(
                    lemmaStr,
                    siteTarget != null ? siteTarget.getId() : null
            );

            if (!foundLemmas.isEmpty()) {
                // Если несколько сайтов — берём первую (или можно логику доработать)
                lemmasForSearch.add(foundLemmas.get(0));
            }
        }

        if (lemmasForSearch.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", true, "count", 0, "data", Collections.emptyList()));
        }

        // 3. Фильтруем слишком частые леммы
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

        // ───────────────────────────────────────────────
        // 4. Поиск страниц, содержащих ВСЕ нужные леммы (пересечение)
        // ───────────────────────────────────────────────
        Map<Integer, Double> pageRelevance = new HashMap<>(); // pageId → суммарная релевантность

        // Начинаем с самой редкой леммы
        Lemma firstLemma = lemmasForSearch.get(0);
        List<IndexSearch> firstIndexes = indexRepository.findIndexesByLemma(firstLemma.getId());

        for (IndexSearch idx : firstIndexes) {
            pageRelevance.put(idx.getPageId(), (double) idx.getLemmaCount());
        }

        // Пересекаем с остальными леммами
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
