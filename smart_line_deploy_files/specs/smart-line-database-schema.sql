-- Smart Line Widget Lake — Database Schema Extensions
-- 
-- Extends existing multi-tenant schema (BC-19, BC-02, BC-16) with:
-- 1. Widget template registry (BC-02: plugin-lifecycle)
-- 2. Widget instance audit log (BC-19: multi-tenant-runtime)
-- 3. Session state archive (BC-16: personalization-runtime)
--
-- Convention:
-- - Tables: bc{NN}_{entity_singular}
-- - Columns: {role}_{field} (e.g., plugin_created_at, learner_updated_at)
-- - PKs: UUID gen_random_uuid()
-- - Timestamps: timestamptz only
-- - Comments: MANDATORY on every table and column

-- ─────────────────────────────────────────────────────────
-- BC-02: plugin-lifecycle schema
-- ─────────────────────────────────────────────────────────

-- table: bc02_widget_template
-- purpose: Registry of widget types exposed by plugins (Smart Line compatible)
CREATE TABLE IF NOT EXISTS bc02_widget_template (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Foreign key to plugin registry
  plugin_id VARCHAR(255) NOT NULL REFERENCES bc02_plugin(id) ON DELETE CASCADE,
  
  -- Widget identity
  widget_type VARCHAR(100) NOT NULL,
  widget_name VARCHAR(255) NOT NULL,
  widget_description TEXT,
  
  -- Interaction levels supported (L1, L2, L3, L4, L5)
  supported_interaction_levels VARCHAR(50)[] DEFAULT '{"L1"}',
  
  -- Student Lifecycle Spaces where widget can appear
  supported_spaces VARCHAR(50)[] DEFAULT '{"LEARNING"}',
  
  -- DivKit rendering capabilities
  divkit_renderer_enabled BOOLEAN DEFAULT true,
  divkit_version VARCHAR(20) DEFAULT '1.x',
  divkit_custom_components VARCHAR(255)[],
  
  -- Configuration schema (JSON Schema, optional)
  config_schema JSONB,
  
  -- Audit fields
  plugin_created_at TIMESTAMPTZ DEFAULT NOW(),
  plugin_updated_at TIMESTAMPTZ DEFAULT NOW(),
  
  -- Uniqueness constraints
  UNIQUE(plugin_id, widget_type),
  CHECK(array_length(supported_interaction_levels, 1) > 0)
);

COMMENT ON TABLE bc02_widget_template IS
  'Registry of widget types exposed by plugins. Smart Line uses this to discover available widgets.';
COMMENT ON COLUMN bc02_widget_template.widget_type IS
  'e.g., AskUserQuestion, ExplanationWidget, ProgressChart';
COMMENT ON COLUMN bc02_widget_template.supported_interaction_levels IS
  'Array of L1-L5 levels this widget supports. L1=Display, L5=Full Skill Launch';
COMMENT ON COLUMN bc02_widget_template.supported_spaces IS
  'Student Lifecycle Spaces where this widget can appear (LEARNING, ANALYTICS, etc.)';

-- Index for fast lookup by plugin
CREATE INDEX idx_bc02_widget_template_by_plugin
  ON bc02_widget_template(plugin_id);

-- Index for space-based queries
CREATE INDEX idx_bc02_widget_template_by_space
  ON bc02_widget_template USING GIN(supported_spaces);

-- Index for type lookup
CREATE INDEX idx_bc02_widget_template_by_type
  ON bc02_widget_template(widget_type);

-- ─────────────────────────────────────────────────────────
-- BC-19: multi-tenant-runtime schema
-- ─────────────────────────────────────────────────────────

-- table: bc19_widget_instance
-- purpose: Current widget instances for a learner session (hot cache)
CREATE TABLE IF NOT EXISTS bc19_widget_instance (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Multi-tenant isolation
  tenant_id UUID NOT NULL REFERENCES bc19_tenant(id) ON DELETE CASCADE,
  
  -- Session & learner context
  session_id UUID NOT NULL,
  learner_id UUID NOT NULL REFERENCES bc19_learner(id) ON DELETE CASCADE,
  
  -- Widget identity
  widget_id VARCHAR(100) NOT NULL,
  widget_type VARCHAR(100) NOT NULL,
  widget_name VARCHAR(255),
  
  -- Space context
  space VARCHAR(50) NOT NULL,  -- LEARNING, ANALYTICS, etc.
  
  -- Lifecycle state
  state VARCHAR(50) DEFAULT 'CREATED',  -- CREATED, RENDERED, INTERACTED, ARCHIVED
  interaction_level VARCHAR(10),  -- L1, L2, L3, L4, L5
  
  -- DivKit JSON (immutable after creation for audit)
  div_data JSONB NOT NULL,
  div_data_hash VARCHAR(64),  -- SHA256 hash for deduplication
  
  -- Metadata
  source_plugin VARCHAR(255),
  rank_score NUMERIC(3,2),  -- 0.00-1.00
  ttl_seconds INTEGER DEFAULT 300,
  payload JSONB,  -- Plugin-specific data (question ID, etc.)
  
  -- Audit timestamps
  plugin_created_at TIMESTAMPTZ DEFAULT NOW(),
  plugin_expired_at TIMESTAMPTZ,
  plugin_updated_at TIMESTAMPTZ DEFAULT NOW(),
  
  -- Multi-column uniqueness: (tenant, session, widget_id)
  UNIQUE(tenant_id, session_id, widget_id),
  CHECK(state IN ('CREATED', 'RENDERED', 'INTERACTED', 'ARCHIVED')),
  CHECK(interaction_level IS NULL OR interaction_level IN ('L1', 'L2', 'L3', 'L4', 'L5'))
);

COMMENT ON TABLE bc19_widget_instance IS
  'Current widget instances for active learner sessions. Hot cache (synced with Redis).';
COMMENT ON COLUMN bc19_widget_instance.state IS
  'Lifecycle: CREATED (generated) → RENDERED (to UI) → INTERACTED (user action) → ARCHIVED';
COMMENT ON COLUMN bc19_widget_instance.div_data IS
  'Full DivKit JSON structure (immutable). Hashed for deduplication.';
COMMENT ON COLUMN bc19_widget_instance.div_data_hash IS
  'SHA256 checksum of div_data for detecting changes without re-parsing JSON';

CREATE INDEX idx_bc19_widget_instance_by_session
  ON bc19_widget_instance(tenant_id, session_id);

CREATE INDEX idx_bc19_widget_instance_by_learner
  ON bc19_widget_instance(tenant_id, learner_id);

CREATE INDEX idx_bc19_widget_instance_by_state
  ON bc19_widget_instance(state) WHERE state != 'ARCHIVED';

-- table: bc19_widget_instance_audit
-- purpose: Audit trail of all widget changes (immutable log for compliance)
CREATE TABLE IF NOT EXISTS bc19_widget_instance_audit (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Tenant & learner context
  tenant_id UUID NOT NULL REFERENCES bc19_tenant(id) ON DELETE CASCADE,
  learner_id UUID NOT NULL REFERENCES bc19_learner(id) ON DELETE CASCADE,
  session_id UUID NOT NULL,
  
  -- Widget reference
  widget_id VARCHAR(100) NOT NULL,
  widget_type VARCHAR(100) NOT NULL,
  space VARCHAR(50) NOT NULL,
  
  -- State transition
  previous_state VARCHAR(50),
  new_state VARCHAR(50) NOT NULL,
  state_changed_reason VARCHAR(255),  -- "user_submitted", "ttl_expired", etc.
  
  -- Interaction metadata
  interaction_level VARCHAR(10),
  user_input JSONB,  -- Student answer, selected options, etc. (encrypted in prod)
  evaluation_result JSONB,  -- { correct: bool, score: 0.0-1.0, feedback: "..." }
  
  -- Student state after action
  student_state_after JSONB,  -- { attempts: int, mastery_score: float, ... }
  
  -- DivKit snapshot
  div_data_snapshot JSONB,
  
  -- Audit timestamps (immutable)
  created_at TIMESTAMPTZ DEFAULT NOW(),
  event_ts TIMESTAMPTZ DEFAULT NOW(),
  
  -- TTL for compliance (GDPR: 90 days minimum, 7 years max)
  expires_at TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '7 years'),
  
  FOREIGN KEY (tenant_id, session_id) REFERENCES bc19_widget_instance(tenant_id, session_id) ON DELETE SET NULL,
  CHECK(new_state IN ('CREATED', 'RENDERED', 'INTERACTED', 'ARCHIVED'))
);

COMMENT ON TABLE bc19_widget_instance_audit IS
  'Immutable audit trail for compliance, analytics, and replay. Not for real-time queries.';
COMMENT ON COLUMN bc19_widget_instance_audit.user_input IS
  'Raw student input (should be encrypted at rest in production). Used for learning.';
COMMENT ON COLUMN bc19_widget_instance_audit.evaluation_result IS
  'Evaluation result from EvaluatorAgent (score, correctness, misconceptions).';

CREATE INDEX idx_bc19_widget_instance_audit_by_session
  ON bc19_widget_instance_audit(tenant_id, session_id, created_at DESC);

CREATE INDEX idx_bc19_widget_instance_audit_by_learner
  ON bc19_widget_instance_audit(tenant_id, learner_id, created_at DESC);

CREATE INDEX idx_bc19_widget_instance_audit_by_expiry
  ON bc19_widget_instance_audit(expires_at) WHERE expires_at IS NOT NULL;

-- table: bc19_widget_space_config
-- purpose: Per-tenant configuration for Student Lifecycle Spaces
CREATE TABLE IF NOT EXISTS bc19_widget_space_config (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  tenant_id UUID NOT NULL UNIQUE REFERENCES bc19_tenant(id) ON DELETE CASCADE,
  space VARCHAR(50) NOT NULL,
  
  -- Enabled/disabled
  is_enabled BOOLEAN DEFAULT true,
  
  -- Available widget types for this space
  enabled_widget_types VARCHAR(100)[],
  disabled_widget_types VARCHAR(100)[],
  
  -- Available plugins
  enabled_plugins VARCHAR(255)[],
  
  -- Ordering strategy
  widget_ordering_strategy VARCHAR(50) DEFAULT 'rank_score_descending',
  
  -- Limits
  max_widgets_on_screen INTEGER DEFAULT 5,
  widget_refresh_interval_seconds INTEGER DEFAULT 300,
  
  -- Audit
  tenant_created_at TIMESTAMPTZ DEFAULT NOW(),
  tenant_updated_at TIMESTAMPTZ DEFAULT NOW(),
  
  UNIQUE(tenant_id, space),
  CHECK(widget_ordering_strategy IN ('rank_score_descending', 'priority_descending', 'custom'))
);

COMMENT ON TABLE bc19_widget_space_config IS
  'Tenant-specific configuration for Student Lifecycle Spaces (LEARNING, ANALYTICS, etc.).';
COMMENT ON COLUMN bc19_widget_space_config.disabled_widget_types IS
  'Widgets explicitly excluded for this tenant (e.g., "PeerActivity" for privacy)';

CREATE INDEX idx_bc19_widget_space_config_by_space
  ON bc19_widget_space_config(tenant_id, space);

-- ─────────────────────────────────────────────────────────
-- BC-16: personalization-runtime schema
-- ─────────────────────────────────────────────────────────

-- table: bc16_student_session
-- purpose: Long-term Student session archive (from Redis decay)
CREATE TABLE IF NOT EXISTS bc16_student_session (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Session identity
  session_id UUID NOT NULL UNIQUE,
  tenant_id UUID NOT NULL,
  learner_id UUID NOT NULL,
  
  -- Topic & space
  topic VARCHAR(255),
  space VARCHAR(50),
  
  -- Final state (snapshot from StudentState)
  final_attempts INTEGER,
  final_mastery_score NUMERIC(3,2),
  final_consecutive_wrong INTEGER,
  final_misconceptions TEXT[],
  
  -- Session duration
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  duration_seconds INTEGER,
  
  -- Termination
  termination_reason VARCHAR(100),  -- "mastered", "max_attempts", "timeout", "user_exit"
  
  -- Audit
  created_at TIMESTAMPTZ DEFAULT NOW(),
  
  CHECK(termination_reason IN ('mastered', 'max_attempts', 'timeout', 'user_exit'))
);

COMMENT ON TABLE bc16_student_session IS
  'Archive of completed Smart Line sessions (from Redis decay). For long-term analytics.';
COMMENT ON COLUMN bc16_student_session.final_state IS
  'Snapshot of StudentState at session end.';

CREATE INDEX idx_bc16_student_session_by_learner
  ON bc16_student_session(learner_id, ended_at DESC);

CREATE INDEX idx_bc16_student_session_by_topic
  ON bc16_student_session(topic, ended_at DESC);

-- ─────────────────────────────────────────────────────────
-- VIEWS (for analytics queries)
-- ─────────────────────────────────────────────────────────

-- view: widget_performance_summary
-- Shows aggregate stats on widget usage and effectiveness
CREATE OR REPLACE VIEW widget_performance_summary AS
SELECT
  wa.widget_type,
  wa.space,
  COUNT(*) AS interaction_count,
  COUNT(DISTINCT wa.learner_id) AS unique_learners,
  AVG((wa.evaluation_result->>'score')::NUMERIC) AS avg_score,
  SUM(CASE WHEN (wa.evaluation_result->>'correct')::BOOLEAN THEN 1 ELSE 0 END) AS correct_count,
  MIN(wa.event_ts) AS first_interaction,
  MAX(wa.event_ts) AS last_interaction
FROM bc19_widget_instance_audit wa
WHERE wa.created_at > NOW() - INTERVAL '30 days'
GROUP BY wa.widget_type, wa.space
ORDER BY interaction_count DESC;

COMMENT ON VIEW widget_performance_summary IS
  'Aggregate widget performance metrics (last 30 days). Used for recommendations & ML training.';

-- ─────────────────────────────────────────────────────────
-- MIGRATION NOTES
-- ─────────────────────────────────────────────────────────

/*
To apply these migrations:

  1. Create tables:
       psql -h localhost -d adapstory -f smart-line-ddl.sql

  2. Verify:
       SELECT table_name FROM information_schema.tables 
       WHERE table_schema = 'public' AND table_name LIKE 'bc%widget%';

  3. Test inserts:
       INSERT INTO bc02_widget_template (...) VALUES (...);
       INSERT INTO bc19_widget_instance (...) VALUES (...);

  4. Index stats (for query planner):
       ANALYZE bc02_widget_template;
       ANALYZE bc19_widget_instance;
       ANALYZE bc19_widget_instance_audit;

  5. Backup before production:
       pg_dump -h localhost -d adapstory -t bc02_widget_template > backup.sql
*/

-- EOF
