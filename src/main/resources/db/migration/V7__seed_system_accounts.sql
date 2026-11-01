-- System accounts, one per supported currency (an account has exactly one currency).
--   platform_fees:<CUR>        REVENUE  fees the platform earns
--   external_settlement:<CUR>  ASSET    money entering/leaving the system (fundings, payouts)
INSERT INTO accounts (id, code, type, currency, allow_negative)
SELECT gen_random_uuid(), 'platform_fees:' || c, 'REVENUE', c, FALSE
FROM unnest(ARRAY ['USD', 'EUR', 'GBP', 'INR']) AS c;

INSERT INTO accounts (id, code, type, currency, allow_negative)
SELECT gen_random_uuid(), 'external_settlement:' || c, 'ASSET', c, TRUE
FROM unnest(ARRAY ['USD', 'EUR', 'GBP', 'INR']) AS c;
