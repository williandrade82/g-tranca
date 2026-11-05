-- §18.1 (mudanca de 02/11/2026): contas por e-mail e senha tambem tem carteira e inventario online (§18.5),
-- igual as contas Google.
ALTER TABLE conta DROP CONSTRAINT conta_tipo_check;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_check CHECK (tipo IN ('CONVIDADO', 'GOOGLE', 'EMAIL'));
