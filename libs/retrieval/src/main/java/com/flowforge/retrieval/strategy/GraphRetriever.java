package com.flowforge.retrieval.strategy;

import com.flowforge.graph.query.Neo4jGraphQueryService;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GraphRetriever {

    private final Neo4jGraphQueryService graphQuery;

    public GraphRetriever(Neo4jGraphQueryService graphQuery) {
        this.graphQuery = graphQuery;
    }

    public List<RankedDocument> retrieve(RetrievalRequest request) {
        var documents = new ArrayList<RankedDocument>();

        request.serviceName().ifPresent(service -> {
            int hops = request.graphHops().orElse(2);
            var neighbors = graphQuery.getServiceNeighborhood(service, hops);
            documents.add(new RankedDocument(
                formatGraphContext("Service neighborhood for " + service, neighbors),
                1.0,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "neighborhood", "service", service, "hops", hops)
            ));
        });

        var endpoints = graphQuery.searchEndpoints(request.query());
        if (!endpoints.isEmpty()) {
            documents.add(new RankedDocument(
                formatGraphContext("Relevant endpoints", endpoints),
                0.9,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "endpoints")
            ));
        }

        var complexMethods = graphQuery.findComplexReactiveMethods();
        if (!complexMethods.isEmpty()) {
            documents.add(new RankedDocument(
                formatGraphContext("Complex reactive methods", complexMethods),
                0.7,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "reactive_complex")
            ));
        }

        return documents;
    }

    private static String formatGraphContext(String label, Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ", label + ": [", "]"));
        }
        return label + ": " + value;
    }
}
