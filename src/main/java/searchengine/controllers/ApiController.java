package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import searchengine.config.SitesList;
import searchengine.dto.responses.OkResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.SitePage;
import searchengine.services.ApiService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final SearchService searchService;
    private final StatisticsService statisticsService;
    private final ApiService apiService;
    private final AtomicBoolean indexingProcessing = new AtomicBoolean(false);
    private final SitesList sitesList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @GetMapping("/statistics")
    public StatisticsResponse statistics() throws MalformedURLException {
        return statisticsService.getStatistics();
    }

    @GetMapping("/startIndexing")
    public OkResponse startIndexing() {
        if (indexingProcessing.get()) {
            throw new IllegalStateException("Индексация уже запущена");
        }
        executor.submit(() -> {
            indexingProcessing.set(true);
            apiService.startIndexing(indexingProcessing);
        });
        return new OkResponse();
    }

    @GetMapping("/stopIndexing")
    public OkResponse stopIndexing() {
        if (!indexingProcessing.get()) {
            throw new IllegalStateException("Индексация не запущена");
        }
        indexingProcessing.set(false);
        return new OkResponse();
    }

    @PostMapping("/indexPage")
    public OkResponse indexPage(@RequestParam String url) throws IOException {
        URL refUrl = new URL(url);
        SitePage sitePage = sitesList.getSites().stream()
                .filter(site -> refUrl.getHost().equals(site.getUrl().getHost()))
                .findFirst()
                .map(site -> {
                    SitePage sp = new SitePage();
                    sp.setName(site.getName());
                    sp.setUrl(site.getUrl().toString());
                    return sp;
                })
                .orElseThrow(() -> new IllegalArgumentException(
                        "Данная страница находится за пределами сайтов указанных в конфигурационном файле"
                ));

        apiService.refreshPage(sitePage, refUrl);
        return new OkResponse();
    }

    @GetMapping("/search")
    public Object search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") Integer offset,
            @RequestParam(defaultValue = "20") Integer limit
    ) throws IOException {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Задан пустой поисковый запрос");
        }
        return searchService.search(query, site, offset, limit);
    }
}

