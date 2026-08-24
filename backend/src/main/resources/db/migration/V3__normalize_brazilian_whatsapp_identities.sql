ALTER TABLE whatsapp_identities
    ADD COLUMN normalized_phone_number TEXT GENERATED ALWAYS AS (
        CASE
            WHEN phone_number ~ '^whatsapp:\+55[1-9][0-9][6-9][0-9]{7}$'
                THEN substring(phone_number FROM 1 FOR 14) || '9' || substring(phone_number FROM 15)
            ELSE phone_number
        END
    ) STORED;

ALTER TABLE whatsapp_identities
    ADD CONSTRAINT uq_whatsapp_identities_normalized_phone_number UNIQUE (normalized_phone_number);
