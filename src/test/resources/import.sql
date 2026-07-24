CREATE UNIQUE INDEX IF NOT EXISTS uq_active_lease_per_member
    ON session_leases (member_id)
    WHERE status = 'ACTIVE';
