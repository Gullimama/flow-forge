package com.flowforge.retrieval.fusion;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.RetrievalTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion rrf = new ReciprocalRankFusion();

    @Test
    void fuse_singleList_preservesOriginalOrder() {
        var list = List.of(
            RetrievalTestFixtures.rankedDoc("doc-A", 0.9, RankedDocument.DocumentSource.VECTOR_CODE),
            RetrievalTestFixtures.rankedDoc("doc-B", 0.7, RankedDocument.DocumentSource.VECTOR_CODE)
        );
        var fused = rrf.fuse(List.of(list));

        assertThat(fused).extracting(RankedDocument::content)
            .containsExactly("doc-A", "doc-B");
    }

    @Test
    void fuse_duplicateAcrossLists_getsBoostedScore() {
        var vectorList = List.of(
            RetrievalTestFixtures.rankedDoc("shared-doc", 0.9, RankedDocument.DocumentSource.VECTOR_CODE),
            RetrievalTestFixtures.rankedDoc("vector-only", 0.8, RankedDocument.DocumentSource.VECTOR_CODE)
        );
        var bm25List = List.of(
            RetrievalTestFixtures.rankedDoc("shared-doc", 5.0, RankedDocument.DocumentSource.BM25_CODE),
            RetrievalTestFixtures.rankedDoc("bm25-only", 3.0, RankedDocument.DocumentSource.BM25_CODE)
        );
        var fused = rrf.fuse(List.of(vectorList, bm25List));

        assertThat(fused.get(0).content()).isEqualTo("shared-doc");
        double sharedScore = fused.get(0).score();
        double singleSourceScore = fused.stream()
            .filter(d -> d.content().equals("vector-only"))
            .findFirst().orElseThrow().score();
        assertThat(sharedScore).isGreaterThan(singleSourceScore);
    }

    @Test
    void fuse_emptyLists_returnsEmpty() {
        var fused = rrf.fuse(List.of(List.of(), List.of()));
        assertThat(fused).isEmpty();
    }

    @Test
    void fuse_rrfScoreFormula_isCorrect() {
        var list = List.of(
            RetrievalTestFixtures.rankedDoc("first", 1.0, RankedDocument.DocumentSource.VECTOR_CODE),
            RetrievalTestFixtures.rankedDoc("second", 0.5, RankedDocument.DocumentSource.VECTOR_CODE)
        );
        var fused = rrf.fuse(List.of(list));
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, within(0.0001));
    }

    @Test
    void fuse_fiveSourceLists_deduplicatesCorrectly() {
        var vectorCode = List.of(RetrievalTestFixtures.rankedDoc("A", 0.9, RankedDocument.DocumentSource.VECTOR_CODE));
        var vectorLog = List.of(RetrievalTestFixtures.rankedDoc("B", 0.8, RankedDocument.DocumentSource.VECTOR_LOG));
        var bm25Code = List.of(RetrievalTestFixtures.rankedDoc("A", 5.0, RankedDocument.DocumentSource.BM25_CODE));
        var bm25Log = List.of(RetrievalTestFixtures.rankedDoc("C", 3.0, RankedDocument.DocumentSource.BM25_LOG));
        var graph = List.of(RetrievalTestFixtures.rankedDoc("A", 1.0, RankedDocument.DocumentSource.GRAPH));

        var fused = rrf.fuse(List.of(vectorCode, vectorLog, bm25Code, bm25Log, graph));

        long distinctContents = fused.stream().map(RankedDocument::content).distinct().count();
        assertThat(distinctContents).isEqualTo(fused.size());
        assertThat(fused.get(0).content()).isEqualTo("A");
    }
}
