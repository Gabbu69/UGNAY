-- A discovery run with no eligible candidate has no confidence measurement.
-- Preserve that absence as NULL instead of persisting a fabricated zero.
ALTER TABLE discovery_runs MODIFY COLUMN confidence_score DECIMAL(5,2) NULL;
