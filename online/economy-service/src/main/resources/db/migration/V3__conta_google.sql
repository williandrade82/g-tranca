-- §18.5 / plano 2.6: a carteira do convidado passa para a conta Google uma unica vez.
-- migrada_para: no convidado, a conta que recebeu a carteira dele. origem_convidado: na conta, de quem veio.
ALTER TABLE conta ADD COLUMN migrada_para TEXT REFERENCES conta (id);
ALTER TABLE conta ADD COLUMN origem_convidado TEXT REFERENCES conta (id);
