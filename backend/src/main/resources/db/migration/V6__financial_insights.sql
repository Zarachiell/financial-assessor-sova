ALTER TABLE categories
    ADD COLUMN expense_nature TEXT NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT ck_categories_expense_nature
        CHECK (expense_nature IN ('FIXED', 'DISCRETIONARY', 'UNCLASSIFIED')),
    ADD CONSTRAINT ck_categories_income_unclassified
        CHECK (type = 'EXPENSE' OR expense_nature = 'UNCLASSIFIED');

UPDATE categories
SET expense_nature = CASE
    WHEN type <> 'EXPENSE' THEN 'UNCLASSIFIED'
    WHEN lower(name) IN ('moradia', 'saúde') THEN 'FIXED'
    WHEN lower(name) IN ('restaurante', 'lazer', 'compras') THEN 'DISCRETIONARY'
    ELSE 'UNCLASSIFIED'
END;

CREATE INDEX ix_transactions_household_type_occurred_category
    ON transactions (household_id, type, occurred_on, category_id);
