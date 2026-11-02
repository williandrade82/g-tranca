-- Sec18.7: equipe do console de gerenciamento (papeis leitor/operador/administrador). So o hash bcrypt da senha e
-- gravado, nunca a senha (mesma tecnica da conta de jogador, Sec18.1, implementada aqui sem depender do lobby-service).
CREATE TABLE membro (
    id         TEXT PRIMARY KEY,
    email      TEXT NOT NULL UNIQUE,
    senha_hash TEXT NOT NULL,
    papel      TEXT NOT NULL CHECK (papel IN ('LEITOR', 'OPERADOR', 'ADMINISTRADOR')),
    criado_ms  BIGINT NOT NULL
);

-- Sessao (token Bearer opaco; so o hash SHA-256 e gravado, nunca o token). Curta de proposito: console administrativo.
CREATE TABLE sessao (
    token_hash    TEXT PRIMARY KEY,
    membro_id     TEXT NOT NULL REFERENCES membro (id) ON DELETE CASCADE,
    criada_ms     BIGINT NOT NULL,
    ultimo_uso_ms BIGINT NOT NULL
);

CREATE INDEX sessao_membro ON sessao (membro_id);
