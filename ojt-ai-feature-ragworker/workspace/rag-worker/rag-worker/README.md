# rag-worker

## Development dependencies

Install required libraries on the host (for Debian/Ubuntu). The helper script installs AWS SDK for C++, libpq/libpqxx, and related toolchains:

```bash
cd scripts
./install_sdks.sh
```

## Build

```bash
cd rag-worker
mkdir -p build && cd build
cmake ..
make -j
```

## Postgres test mode

With Postgres from docker compose running, execute:

```bash
./rag-worker-dev --test-pg
```

## Kafka demo mode

Ensure the docker compose stack (with Redpanda and kafka-tools) is running, then send the demo event:

```bash
./rag-worker-dev --produce-demo
```

Verify the message with kcat:

```bash
docker exec -it kafka-tools \
  kcat -b redpanda:9092 -t doc_ingest -C -o end -e -c 1
```

## Kafka consume-once mode

Consume one message, apply Postgres idempotent state machine, and commit the offset. Ensure MinIO credentials are exported (e.g., defaults from docker compose):

```bash
export MINIO_ROOT_USER=minio
export MINIO_ROOT_PASSWORD=minio123
./rag-worker-dev --consume-once

# Check document metadata
docker exec -it postgres \
  psql -U rag_user -d rag_db \
  -c "select id,status,chunk_count from kb_document where id='00000000-0000-0000-0000-000000000001';"

# Inspect chunk rows
docker exec -it postgres \
  psql -U rag_user -d rag_db \
  -c "select seq_no,length(content) from kb_chunk where doc_id='00000000-0000-0000-0000-000000000001' order by seq_no limit 5;"
```

## Embedding and Qdrant upsert

After a document reaches READY, embed its chunks and push vectors to Qdrant:

```bash
export AZURE_OPENAI_ENDPOINT=https://deepseek-ivis-rag.openai.azure.com
export AZURE_OPENAI_API_KEY=...
export AZURE_OPENAI_API_VERSION=2024-02-15-preview
export AZURE_OPENAI_EMBEDDING_DEPLOYMENT=text-embedding-3-large
export QDRANT_URL=http://qdrant:6333
./rag-worker-dev --embed-doc 00000000-0000-0000-0000-000000000001

# Verify collection
curl -s http://qdrant:6333/collections/tenant-001__kb-001 | jq .
curl -s http://qdrant:6333/collections/tenant-001__kb-001/points/count \
  -H "Content-Type: application/json" \
  -d '{"exact": true}' | jq .
```

## Query search

Embed a query and retrieve from Qdrant:

```bash
./rag-worker-dev --search "demo 文档中的关键词" --tenant tenant-001 --kb kb-001 --topk 5
```

## RAG answering

Answer a question using Azure OpenAI Responses with citations:

```bash
export AZURE_OPENAI_CHAT_DEPLOYMENT=DeepSeek-V3.2
export AZURE_OPENAI_CHAT_API_VERSION=2024-05-01-preview
export AZURE_OPENAI_CHAT_ENDPOINT=https://deepseek-ivis-rag.services.ai.azure.com/openai/v1
./rag-worker-dev --answer "demo 文档的用途是什么？" --tenant tenant-001 --kb kb-001 --topk 5
```

The command:

1. Embeds the question
2. Retrieves Top-K chunks from Qdrant
3. Builds the locked system/user prompts
4. Calls Azure OpenAI (Responses endpoint by default; set `AZURE_OPENAI_CHAT_ENDPOINT` to target the `/openai/v1/chat/completions` API if needed) with temperature 0 / max 512 tokens
5. Prints the answer plus per-chunk citations (`doc_id/seq_no/score`)

If retrieval returns no hits, the tool prints `I don't know based on the provided documents.` and still emits an empty `Sources` block.

## Internal HTTP API

Expose search/answer capabilities for debugging or reference integrations:

```bash
./rag-worker-dev --serve
```

## Kafka search/answer worker

Run the compute worker that consumes Kafka requests and publishes results:

```bash
export KAFKA_BROKERS=redpanda:9092
export KAFKA_WORKER_GROUP=rag-core-worker
./rag-worker-dev --kafka-worker
```

The worker listens on:

- `rag_search_request` → `rag_search_result`
- `rag_answer_request` → `rag_answer_result`
- Failures → `rag_failed`

Messages follow the JSON schemas in Step 11 instructions; every offset is committed regardless of success to keep processing idempotent.

Endpoints (JSON only):

* `POST /internal/search` with `query`, `tenant_id`, `kb_id`, `topk`
* `POST /internal/answer` with `question`, `tenant_id`, `kb_id`, `topk`

Example:

```bash
curl -s http://localhost:8080/internal/search \
  -H "Content-Type: application/json" \
  -d '{"query":"demo 文档的用途是什么？","tenant_id":"tenant-001","kb_id":"kb-001","topk":5}' | jq .
```
