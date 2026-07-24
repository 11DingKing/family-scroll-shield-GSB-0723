-- H2-compatible indexes for testing
CREATE INDEX IF NOT EXISTS uq_active_lease_per_member ON session_leases (member_id);
CREATE INDEX IF NOT EXISTS idx_leases_heartbeat_seq ON session_leases (lease_token, heartbeat_seq);
