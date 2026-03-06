# FlowForge deep learning update and best-solution recommendation

## Executive summary

[Download the updated `system-flows-research.md`](sandbox:/mnt/data/system-flows-research.md)

FlowForge remains a deterministic, evidence-first research platform that ingests (a) a private GitHub **master** snapshot with API-triggered baseline + delta refresh by commit SHA and (b) a bulk set of Splunk-export ZIP files stored in an Azure Blob container with **no time-window** semantics. Azure Blob Storage is designed for storing massive amounts of unstructured object data, which fits “ZIP bundles of logs” well. citeturn4search2

The “best solution” version of FlowForge keeps the hard guarantees in classical/deterministic layers (parsing, indexing, graph modelling) and uses deep learning only where it adds maximum leverage:

- **Deep learning (recommended)** for embeddings + grounded generation (self-hosted).
- **Classical/deterministic** for parsing, diffing, indexing, graph reconstruction, confidence scoring, and evidence traceability.

This updated document expands the deep-learning section into a “GraphRAG-style” hybrid retrieval approach: OpenSearch (lexical) + Qdrant (semantic) + Neo4j (graph expansion) before generation. GraphRAG is described as using a knowledge graph in combination with vector search to improve retrieval relevance beyond plain text chunks. citeturn0search12turn0search4

## Where deep learning is used and where it is not

### Deep learning components in FlowForge

Deep learning is used when you enable the following capabilities:

- **Embeddings** for code/config text and correlation-grouped log “windows”, stored in Qdrant as vectors with payload metadata (Qdrant “points” = vector + optional payload; “collections” group points). citeturn0search7turn0search11turn0search3  
- **LLM generation** to produce a condensed, consistent `system-flows-research.md` from retrieved evidence. vLLM provides an HTTP server implementing OpenAI-compatible APIs for serving LLMs in-cluster. citeturn0search2turn0search17  
- **Optional reranking** (DL) to improve precision after hybrid retrieval (not strictly required, but often useful when you have many near-matches across 60+ services).

### Classical / deterministic components

These remain non-DL and should be treated as your truth anchors:

- **GitHub delta refresh** by commit SHA via “compare two commits” (deterministic file change list). citeturn4search1  
- **Parsing & fact extraction** using Tree-sitter’s incremental parsing foundation (concrete syntax trees, efficient updates) plus Java analysers for Micronaut/Spring-specific constructs. citeturn1search0turn1search4  
- **Exact log search and analytics** in OpenSearch (filtered queries, aggregations, failure clustering, etc.). OpenSearch also supports `knn_vector`, but FlowForge’s recommended design keeps embeddings primarily in Qdrant. citeturn1search1turn0search2  
- **Flow graph reconstruction** in Neo4j’s property graph model (nodes + relationships + properties), which is naturally aligned to “multi-hop flow” representation. citeturn1search2  
- **Confidence scoring** based on agreement between (static-only, runtime-only, static+runtime, static+runtime+istio) evidence sources.

## Is deep learning required

Deep learning is **optional**, but strongly recommended if your goal is to produce a **highly condensed, optimised, human-readable** flow document that remains evidence-backed.

- If you skip DL entirely:
  - You can still build the full evidence lake (MinIO), exact indices (OpenSearch), and flow graph (Neo4j), and generate markdown via deterministic templates.
  - You lose semantic clustering (“similar incidents/flows”), name-variant matching, and high-quality summarisation.
  - In that mode, Qdrant becomes optional and can be dropped; OpenSearch remains essential.

- If you enable DL (recommended):
  - Embeddings improve discovery and de-duplication at scale.
  - LLM generation improves readability and consistency, but must be constrained to **grounded generation** where every statement must cite an evidence pointer.

## Best solution architecture for deep learning and similar techniques

The best-performing design for FlowForge (given your constraints and end goal) is a **hybrid GraphRAG pipeline**:

- **Hybrid retrieval**:
  - OpenSearch lexical retrieval (filters + BM25-style keyword relevance + aggregations).
  - Qdrant semantic retrieval (embeddings).
  - Merge candidates and optionally rerank.
- **Graph expansion**:
  - Use Neo4j to expand neighbourhoods/paths for candidate entities (services/endpoints/topics) and reconstruct explicit call chains and runtime edges (this is the “flow truth” layer).
  - This corresponds to the GraphRAG description of combining graph context with vector retrieval to reach more relevant information. citeturn0search12turn0search4  
- **Grounded synthesis**:
  - Use vLLM to generate the final flow sections, but enforce:
    - mandatory evidence pointers (code path + commit SHA; manifest/istio object; log excerpt pointer)
    - confidence label
    - rewrite annotations (virtual-thread fit; Dapr invocation/pubsub/workflow suitability)

Two critical implementation details the updated document adds:

- **Azure Blob ingestion via Dapr binding is viable**: the Dapr Azure Blob binding supports operations `list` and `get`, which fits an API-triggered “ingest all ZIPs present” mode without time windows. citeturn0search1  
- **Qdrant payload indexing discipline matters**: Qdrant recommends creating payload indexes immediately after collection creation (creating later may block updates), and payload indexing is central for filtered semantic retrieval (e.g., restrict results by `service_id` or `snapshot_id`). citeturn0search1turn0search3  

## Model size guidance and resource trade-offs

Exact model selection remains **explicitly unspecified** (licences, GPU budget, latency SLOs, and throughput targets must drive the choice). The updated document recommends a pragmatic starting envelope:

- **Embeddings**: small-to-medium embedding model to keep batch throughput high (CPU or modest GPU); cache by content hash and model ID.
- **Generation**:
  - start with ~7–8B instruct-class model if GPU budget is limited
  - move to 13B+ if you need better synthesis quality over complex flow graphs and can afford the compute

Virtual threads are a stable concurrency target for the later rewrite (finalised in JDK 21 per JEP 444). citeturn4search0turn4search20  

## What was updated in the downloadable document

The updated `system-flows-research.md` now includes:

- a clearer “best solution” recommendation: deterministic core + embeddings + grounded generation + GraphRAG retrieval
- a more explicit breakdown of deep learning vs non-deep-learning parts
- expanded guidance on reranking, caching, and payload indexing
- strengthened citations to primary/official component documentation (Dapr workflow/binding, vLLM, Qdrant, OpenSearch, Neo4j, Tree-sitter, Azure Blob, GitHub compare API) citeturn4search3turn0search2turn0search7turn1search1turn1search2turn1search0turn4search2turn4search1