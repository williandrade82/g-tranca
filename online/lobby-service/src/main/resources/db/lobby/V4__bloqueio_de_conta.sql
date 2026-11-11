-- S18.7 (03/11/2026): bloqueio de conta pelo console de gerenciamento. Independente do tipo de conta
-- (E_/G_/convidado): a linha existir = bloqueada; apagar a linha = desbloqueada (sem efeito retroativo, decisao do
-- usuario em definition.md S18.7). "Encerrar sessoes" e acao separada, so sobre o token (ver tabela convidado).
CREATE TABLE bloqueio_conta (
    conta_id     TEXT PRIMARY KEY,
    motivo       TEXT,
    bloqueada_em BIGINT NOT NULL
);
