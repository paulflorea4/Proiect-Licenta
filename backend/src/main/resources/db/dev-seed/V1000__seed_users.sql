-- DEV-ONLY seed data (1.4a). This location is on the Flyway path only under the `dev` profile
-- and is never part of the production migration path (db/migration).
--
-- Versions here start at 1000 so they sort after every schema migration (V1..V999).
--
-- Dev logins. These passwords are public, for local development only; the column holds real
-- BCrypt hashes (strength 10), so they work with the same password encoder as real accounts.
--
--   admin@dev.example.com     ADMIN    Admin-dev-1
--   teacher@dev.example.com   TEACHER  Teacher-dev-1
--   student1@dev.example.com  STUDENT  Student-dev-1
--   student2@dev.example.com  STUDENT  Student-dev-2

INSERT INTO users (email, password_hash, full_name, role) VALUES
    ('admin@dev.example.com',    '$2a$10$2hSrRl4tBebb6vPvGWU7OeXPL.81ZxvPla8kDcSrSkiy5ZfItVJMO', 'Ada Admin',      'ADMIN'),
    ('teacher@dev.example.com',  '$2a$10$KIEsXAX6w90KYUxfZn0CiultQsmJgo.7GJwkrTDgUG.0tijzuxsMq', 'Tom Teacher',    'TEACHER'),
    ('student1@dev.example.com', '$2a$10$QqW8aM2sEjkfZXGwB2kXK.YNFWt.ZWJNPcbsmZktjzXfk9h5U3Efi', 'Sara Student',   'STUDENT'),
    ('student2@dev.example.com', '$2a$10$kXfkTFLUVNH9SkSI.bIsSuYTC/ozZGhk1IUxj77IPoYQCnTPvTf1W', 'Sam Student',    'STUDENT');
