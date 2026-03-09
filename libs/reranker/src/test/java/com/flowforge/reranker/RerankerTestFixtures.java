package com.flowforge.reranker;

import com.flowforge.common.config.FlowForgeProperties;
import java.util.List;
import java.util.stream.IntStream;

public final class RerankerTestFixtures {

    private RerankerTestFixtures() {}

    public static String rerankResponse(int count) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            double score = 1.0 - (i * 0.1);
            sb.append(String.format("{\"index\":%d,\"score\":%.2f,\"text\":\"doc%d\"}", i, score, i));
        }
        sb.append("]");
        return sb.toString();
    }

    public static List<String> texts(int count) {
        return IntStream.range(0, count).mapToObj(i -> "doc" + i).toList();
    }

    public static FlowForgeProperties propsWithRerankerUrl(String url) {
        return new FlowForgeProperties(
            null, null, null, null, null, null, null,
            new FlowForgeProperties.TeiProperties("http://code:8081", "http://log:8082", url));
    }

    public static FlowForgeProperties defaultProps() {
        return propsWithRerankerUrl("http://localhost:8083");
    }
}
