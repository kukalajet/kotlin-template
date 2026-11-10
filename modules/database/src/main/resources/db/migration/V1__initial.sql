CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL CONSTRAINT users_email_key UNIQUE,
  display_name text NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX users_created_at_idx ON users (created_at);
