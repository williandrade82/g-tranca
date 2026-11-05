-- Sec18.7: registro imutavel de toda acao da equipe (quem, quando, o que, por que). So INSERT: as triggers abaixo
-- recusam UPDATE/DELETE/TRUNCATE mesmo para o usuario dono da tabela (REVOKE nao bastaria, o dono sempre pode).
CREATE TABLE auditoria (
    id          BIGSERIAL PRIMARY KEY,
    operacao_id TEXT UNIQUE,                 -- id da operacao (idempotencia): repetir devolve o mesmo resultado
    quando_ms   BIGINT NOT NULL,
    ator_id     TEXT NOT NULL,               -- copiado do membro, sem FK: remover o membro nao apaga o historico
    ator_email  TEXT NOT NULL,
    acao        TEXT NOT NULL,               -- CRIAR_MEMBRO, ALTERAR_MEMBRO, REMOVER_MEMBRO, SEMEAR_ADMINISTRADOR...
    alvo_id     TEXT,
    alvo_email  TEXT,
    motivo      TEXT NOT NULL,
    detalhe     TEXT,
    resultado   TEXT                          -- estado resultante (JSON): repetir a mesma operacao devolve isto
);

CREATE INDEX auditoria_quando ON auditoria (quando_ms);

CREATE OR REPLACE FUNCTION auditoria_imutavel() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'a auditoria e append-only: % nao e permitido', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER auditoria_sem_update_delete
    BEFORE UPDATE OR DELETE ON auditoria
    FOR EACH ROW EXECUTE FUNCTION auditoria_imutavel();

CREATE TRIGGER auditoria_sem_truncate
    BEFORE TRUNCATE ON auditoria
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria_imutavel();
