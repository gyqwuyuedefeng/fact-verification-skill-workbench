ALTER TABLE test.skill_version
    ADD COLUMN comparison_summaries_json jsonb NOT NULL DEFAULT '{}'::jsonb;
