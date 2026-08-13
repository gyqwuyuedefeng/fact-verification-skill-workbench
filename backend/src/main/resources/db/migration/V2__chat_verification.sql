-- 普通核验对话增量：仍只修改既有七表，不新增平台化实体。
ALTER TABLE test.verification_task
    ADD COLUMN user_message text,
    ADD COLUMN input_type varchar(16);

UPDATE test.verification_task
   SET input_type = 'FILE'
 WHERE input_type IS NULL;

ALTER TABLE test.verification_task
    ALTER COLUMN input_type SET NOT NULL,
    ADD CONSTRAINT ck_verification_task_input_type
        CHECK (input_type IN ('TEXT', 'FILE', 'COMBINED')),
    ADD CONSTRAINT ck_verification_task_user_message_length
        CHECK (user_message IS NULL OR char_length(user_message) <= 20000);

ALTER TABLE test.verification_run
    ADD COLUMN variant_type varchar(16) NOT NULL DEFAULT 'SKILL';

ALTER TABLE test.verification_run
    ALTER COLUMN skill_version_id DROP NOT NULL,
    ADD CONSTRAINT ck_verification_run_variant
        CHECK (
            (variant_type = 'BASELINE' AND skill_version_id IS NULL AND run_type = 'PRIMARY')
            OR (variant_type = 'SKILL' AND skill_version_id IS NOT NULL)
        );
