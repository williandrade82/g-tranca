-- §18.1 (mudanca de 02/11/2026): contas por e-mail e senha. O e-mail e normalizado (minusculas, sem espacos nas
-- pontas) pelo servidor antes de gravar; nunca a senha em texto puro, so o hash bcrypt.
CREATE TABLE conta_email (
    id          TEXT PRIMARY KEY,   -- id da conta (prefixo E_)
    email       TEXT NOT NULL UNIQUE,
    senha_hash  TEXT NOT NULL,
    nome        TEXT NOT NULL,
    criada_ms   BIGINT NOT NULL
);
