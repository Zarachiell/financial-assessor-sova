CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    whatsapp_message_id UUID NOT NULL REFERENCES whatsapp_messages (id),
    media_index INTEGER NOT NULL,
    media_url TEXT NOT NULL,
    content_type TEXT NOT NULL,
    media_type TEXT NOT NULL,
    size_bytes BIGINT,
    status TEXT NOT NULL,
    transcription_provider TEXT,
    transcription_model TEXT,
    transcription_duration_ms BIGINT,
    transcription_language TEXT,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attachments_message_index UNIQUE (whatsapp_message_id, media_index),
    CONSTRAINT ck_attachments_media_index CHECK (media_index >= 0),
    CONSTRAINT ck_attachments_media_url CHECK (
        media_url ~ '^https://' AND char_length(media_url) <= 2048
    ),
    CONSTRAINT ck_attachments_content_type CHECK (
        btrim(content_type) <> '' AND char_length(content_type) <= 255
    ),
    CONSTRAINT ck_attachments_media_type CHECK (
        media_type IN ('AUDIO', 'IMAGE', 'DOCUMENT', 'OTHER')
    ),
    CONSTRAINT ck_attachments_size_bytes CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_attachments_status CHECK (
        status IN ('RECEIVED', 'DOWNLOADING', 'DOWNLOADED', 'PROCESSING', 'PROCESSED', 'FAILED')
    ),
    CONSTRAINT ck_attachments_transcription_duration CHECK (
        transcription_duration_ms IS NULL OR transcription_duration_ms >= 0
    )
);

CREATE INDEX ix_attachments_whatsapp_message_id
    ON attachments (whatsapp_message_id);

CREATE TABLE processing_jobs (
    id UUID PRIMARY KEY,
    type TEXT NOT NULL,
    reference_id UUID NOT NULL REFERENCES attachments (id),
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processing_jobs_type_reference UNIQUE (type, reference_id),
    CONSTRAINT ck_processing_jobs_type CHECK (type IN ('PROCESS_AUDIO_MESSAGE')),
    CONSTRAINT ck_processing_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_processing_jobs_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_processing_jobs_available
    ON processing_jobs (available_at, created_at)
    WHERE status = 'PENDING';
