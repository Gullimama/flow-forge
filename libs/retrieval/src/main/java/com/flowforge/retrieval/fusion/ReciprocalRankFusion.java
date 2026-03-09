package com.flowforge.retrieval.fusion;

import com.flowforge.retrieval.model.RankedDocument;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReciprocalRankFusion {

    private static final int K = 60;

    public List<RankedDocument> fuse(List<List<RankedDocument>> rankedLists) {
        var scoreMap = new LinkedHashMap<String, FusionEntry>();

        for (var list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                var doc = list.get(rank);
                var key = contentKey(doc);
                scoreMap.computeIfAbsent(key, k -> new FusionEntry(doc))
                    .addScore(1.0 / (K + rank + 1));
            }
        }

        return scoreMap.values().stream()
            .sorted(Comparator.comparingDouble(FusionEntry::fusedScore).reversed())
            .map(entry -> new RankedDocument(
                entry.document.content(),
                entry.fusedScore(),
                entry.document.source(),
                mergeMetadata(entry.document.metadata(), Map.of("rrf_score", entry.fusedScore()))
            ))
            .toList();
    }

    private String contentKey(RankedDocument doc) {
        var hash = doc.metadata().get("content_hash");
        if (hash != null) return hash.toString();
        String content = doc.content();
        return content != null ? content.substring(0, Math.min(100, content.length())) : "";
    }

    private static Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> extra) {
        var merged = new LinkedHashMap<String, Object>(base != null ? base : Map.of());
        merged.putAll(extra);
        return merged;
    }

    private static class FusionEntry {
        final RankedDocument document;
        double score = 0;

        FusionEntry(RankedDocument doc) {
            this.document = doc;
        }

        void addScore(double s) {
            score += s;
        }

        double fusedScore() {
            return score;
        }
    }
}
