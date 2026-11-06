CREATE TABLE IF NOT EXISTS log_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp_ms INTEGER NOT NULL CHECK (timestamp_ms > 0),
    level TEXT NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR', 'DEBUG')),
    service TEXT NOT NULL CHECK (length(trim(service)) > 0),
    message TEXT NOT NULL CHECK (length(trim(message)) > 0),
    response_time_ms INTEGER NOT NULL CHECK (response_time_ms >= 0)
);

CREATE TABLE IF NOT EXISTS log_tags (
    log_id INTEGER NOT NULL REFERENCES log_events(id) ON DELETE CASCADE,
    tag_key TEXT NOT NULL,
    tag_value TEXT NOT NULL,
    PRIMARY KEY (log_id, tag_key)
);

CREATE INDEX IF NOT EXISTS idx_log_events_timestamp ON log_events(timestamp_ms DESC);
CREATE INDEX IF NOT EXISTS idx_log_events_level_timestamp ON log_events(level, timestamp_ms DESC);
CREATE INDEX IF NOT EXISTS idx_log_events_service_timestamp ON log_events(service, timestamp_ms DESC);
CREATE INDEX IF NOT EXISTS idx_log_tags_key_value ON log_tags(tag_key, tag_value);
