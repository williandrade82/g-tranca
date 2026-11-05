-- §18.2 / plano 2.4: o que nao pode se perder quando o pod do lobby reinicia.
-- Convidados: so o hash SHA-256 do token (nunca o token).
CREATE TABLE convidado (
    token_hash   TEXT PRIMARY KEY,
    id           TEXT NOT NULL UNIQUE,
    nome         TEXT NOT NULL,
    ultimo_uso   BIGINT NOT NULL            -- epoch ms; expira por inatividade
);

CREATE TABLE foto (
    convidado_id TEXT PRIMARY KEY,
    base64       TEXT NOT NULL,
    versao       INT  NOT NULL
);

-- Mesas do servidor (pool) com assentos e estado; o jogo em si vive no game-service.
CREATE TABLE mesa (
    id           TEXT PRIMARY KEY,
    dados        TEXT NOT NULL,             -- JSON do estado da mesa
    atualizada   TIMESTAMPTZ NOT NULL DEFAULT now()
);
