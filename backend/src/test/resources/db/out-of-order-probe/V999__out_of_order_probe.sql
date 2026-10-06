-- Test-only (FlywayDevProfileTests): stands in for a schema migration that arrives after the dev
-- seed (V1000+) has already been applied. Lives only in the test classpath.
CREATE TABLE out_of_order_probe (id BIGINT PRIMARY KEY);
