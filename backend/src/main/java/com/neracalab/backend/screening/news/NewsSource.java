package com.neracalab.backend.screening.news;

import java.net.URI;

/** Where a news article was found ({@code news_article.source}). */
public enum NewsSource {

    TAVILY("Tavily search", null),
    EMITENNEWS("EmitenNews", "www.emitennews.com"),
    PASARDANA("Pasardana", "pasardana.id"),
    IDXCHANNEL("IDX Channel", "www.idxchannel.com"),
    INVESTOR_ID("Investor.id", "investor.id");

    private final String label;
    private final String host;

    NewsSource(String label, String host) {
        this.label = label;
        this.host = host;
    }

    public String label() {
        return label;
    }

    /** Host of a crawled site; null for the search API. */
    public String host() {
        return host;
    }

    /** The crawled site an URL belongs to, or null. */
    public static NewsSource ofUrl(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (host == null) {
            return null;
        }
        for (NewsSource source : values()) {
            if (source.host != null && (host.equals(source.host) || host.equals(source.host.replaceFirst("^www\\.", ""))
                    || ("www." + host).equals(source.host))) {
                return source;
            }
        }
        return null;
    }
}
