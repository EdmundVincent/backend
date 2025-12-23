-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Documents metadata
CREATE TABLE IF NOT EXISTS kb_document (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id text NOT NULL,
  kb_id text NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'ERROR')),
  chunk_count int NOT NULL DEFAULT 0,
  error_message text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Document chunks
CREATE TABLE IF NOT EXISTS kb_chunk (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  doc_id uuid NOT NULL,
  tenant_id text NOT NULL,
  kb_id text NOT NULL,
  seq_no int NOT NULL,
  content text NOT NULL,
  content_sha256 text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Idempotent uniqueness on chunk order per document
CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_chunk_doc_seq ON kb_chunk (doc_id, seq_no);
