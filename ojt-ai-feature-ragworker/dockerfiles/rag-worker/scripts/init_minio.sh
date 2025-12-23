#!/bin/sh
set -e

MINIO_ROOT_USER=${MINIO_ROOT_USER:-minioadmin}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD:-minioadmin}
ALIAS_NAME="local"
BUCKET_NAME="rag-docs"
DEMO_OBJECT="tenants/tenant-001/kb-001/doc-demo.txt"
TMP_FILE="/tmp/doc-demo.txt"

echo "[init_minio] Setting MinIO alias ${ALIAS_NAME}..."
mc alias set "${ALIAS_NAME}" http://minio:9000 "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}"

echo "[init_minio] Ensuring bucket ${BUCKET_NAME} exists..."
mc mb --ignore-existing "${ALIAS_NAME}/${BUCKET_NAME}"

echo "[init_minio] Uploading demo object ${DEMO_OBJECT} (idempotent)..."
if mc stat "${ALIAS_NAME}/${BUCKET_NAME}/${DEMO_OBJECT}" >/dev/null 2>&1; then
  echo "[init_minio] Demo object already present, skipping upload."
else
  mkdir -p "$(dirname "${TMP_FILE}")"
  cat > "${TMP_FILE}" <<'EOF'
MinIO demo document for rag-docs bucket.

This file is intentionally verbose to exceed 5KB. It represents a multi-tenant,
multi-knowledge-base document payload that downstream pipelines may ingest for
vectorization and indexing. The content is placeholder text but mimics the size
and shape of a realistic document. Below is repeated informational text to pad
the size and simulate a larger document body.

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent blandit
fringilla nisi, at tempor lorem bibendum vel. Integer id porttitor sem. Nulla
facilisi. Cras eget eros quis dolor sollicitudin porttitor sed id neque. Sed
lobortis, nunc nec placerat elementum, ante lectus dapibus sapien, in interdum
orci lacus ac leo. Proin ut mi cursus, hendrerit nibh non, tristique mauris.
Vivamus ac velit ac velit pulvinar malesuada. Suspendisse vulputate luctus
lectus, id porta neque ultricies a. Integer suscipit cursus lectus, ut tempor
metus euismod et. Suspendisse at luctus dolor. Integer non lectus ac lorem
placerat gravida. Morbi pretium nibh nec rhoncus pharetra. Integer porttitor
egestas magna, vitae vulputate nibh rhoncus id. Vestibulum tincidunt fermentum
ornare. Cras scelerisque eros vel augue dignissim, nec consequat tortor
sollicitudin.

Curabitur dictum, ex vel pellentesque dapibus, ante magna commodo felis, in
eleifend lorem augue eu nibh. Suspendisse sed placerat nibh, vel interdum ante.
Nullam ut malesuada lectus. Cras vel dolor sed ipsum bibendum ultricies quis
eget metus. Donec cursus lobortis ligula, nec pharetra urna. Proin scelerisque
dui quis urna faucibus, id mattis justo vestibulum. Sed nec arcu ut neque
euismod blandit.

Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia
Curae; Vivamus vel magna at ipsum pulvinar auctor. Vivamus sagittis, magna id
molestie feugiat, nisl sem auctor risus, at porta velit libero id arcu. Mauris
in felis sem. Integer vel enim ac nibh consectetur posuere. Fusce cursus velit
ut sapien commodo, quis tristique dui tincidunt. Vivamus eget fermentum lorem,
ac laoreet sapien. Donec auctor aliquam eros, ut sodales quam bibendum ut.
Suspendisse potenti. Sed posuere lacinia bibendum. Integer luctus consectetur
justo, id viverra magna pulvinar id. Etiam consequat, leo ac fringilla
fermentum, augue nisl tempus ipsum, eget porta nunc ipsum sed elit.

EOF
  # Pad further to ensure size > 5KB
  for i in $(seq 1 60); do
    echo "Line padding ${i}: The quick brown fox jumps over the lazy dog while testing MinIO demo object sizing requirements." >> "${TMP_FILE}"
  done

  mc cp "${TMP_FILE}" "${ALIAS_NAME}/${BUCKET_NAME}/${DEMO_OBJECT}"
  echo "[init_minio] Demo object uploaded."
fi
