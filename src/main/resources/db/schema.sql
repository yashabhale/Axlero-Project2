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

-- Alert rules table
CREATE TABLE IF NOT EXISTS alert_rules (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    query TEXT NOT NULL,
    threshold_count INTEGER NOT NULL CHECK (threshold_count > 0),
    window_minutes INTEGER NOT NULL CHECK (window_minutes > 0),
    schedule_seconds INTEGER NOT NULL CHECK (schedule_seconds > 0),
    cooldown_minutes INTEGER NOT NULL DEFAULT 5 CHECK (cooldown_minutes >= 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    webhook_url TEXT,
    email_recipients TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_rules_enabled ON alert_rules(enabled);

-- Alert history table (fired alerts)
CREATE TABLE IF NOT EXISTS alert_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id TEXT NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    rule_name TEXT NOT NULL,
    query TEXT NOT NULL,
    threshold_count INTEGER NOT NULL,
    actual_count INTEGER NOT NULL,
    window_start_ms INTEGER NOT NULL,
    window_end_ms INTEGER NOT NULL,
    fired_at_ms INTEGER NOT NULL,
    sample_logs TEXT, -- JSON array of sample log hits
    acknowledged INTEGER NOT NULL DEFAULT 0 CHECK (acknowledged IN (0, 1)),
    acknowledged_at_ms INTEGER,
    acknowledged_by TEXT
);

CREATE INDEX IF NOT EXISTS idx_alert_history_rule_id ON alert_history(rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_fired_at ON alert_history(fired_at_ms DESC);
