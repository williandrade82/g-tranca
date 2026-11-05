package com.gtranca.online.lobby

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.TableId
import java.security.SecureRandom
import java.util.Locale
import javax.sql.DataSource

/**
 * Hash de senha (§18.1): bcrypt (nunca a senha em texto puro, nunca um hash generico como SHA-256). A estrategia
 * de senha longa (SHA-512 antes do bcrypt) evita o limite de 72 bytes do bcrypt puro, para nao impor "outra
 * exigencia" alem do minimo de caracteres.
 */
object PasswordHashing {
    private const val DEFAULT_COST = 12
    private val version = BCrypt.Version.VERSION_2A
    private val longPassword = LongPasswordStrategies.hashSha512(version)

    fun hash(password: String, cost: Int = DEFAULT_COST): String =
        BCrypt.with(version, longPassword).hashToString(cost, password.toCharArray())

    fun matches(password: String, hash: String): Boolean =
        runCatching { BCrypt.verifyer(version, longPassword).verify(password.toCharArray(), hash).verified }.getOrDefault(false)
}

/** E-mail normalizado (minusculas, sem espacos nas pontas), ou `null` se vazio, grande demais ou sem forma de e-mail. */
fun normalizeEmail(raw: String): String? {
    val email = raw.trim().lowercase(Locale.ROOT)
    if (email.isEmpty() || email.length > AccountLimits.MAX_EMAIL || email.any { it.isWhitespace() || it.isISOControl() }) return null
    val at = email.indexOf('@')
    if (at <= 0 || at != email.lastIndexOf('@') || at == email.length - 1 || !email.substringAfter('@').contains('.')) return null
    return email
}

/** §18.1: minimo de 6 caracteres, sem outra exigencia (o maximo e so uma defesa tecnica, nao uma regra de produto). */
fun validatePassword(password: String) {
    if (password.length < AccountLimits.MIN_PASSWORD) {
        lobbyError(ErrorCode.REQUISICAO_INVALIDA, "A senha deve ter ao menos ${AccountLimits.MIN_PASSWORD} caracteres.", 400)
    }
    if (password.length > AccountLimits.MAX_PASSWORD) {
        lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Senha grande demais.", 400)
    }
}

/** Senha temporaria aleatoria (§18.1, rota interna de reset): alfabeto sem caracteres ambiguos, como o id da mesa. */
object TemporaryPassword {
    private const val LENGTH = 10
    private val secure = SecureRandom()

    fun generate(): String = buildString(LENGTH) { repeat(LENGTH) { append(TableId.ALPHABET[secure.nextInt(TableId.ALPHABET.length)]) } }
}

/** Onde as contas de e-mail e senha sao gravadas (plano 2.4, mesmo esquema do resto do lobby). */
interface EmailAccountPersistence {
    data class StoredAccount(val id: String, val email: String, val passwordHash: String, val name: String, val createdMs: Long)

    fun loadAll(): List<StoredAccount>

    /** `false` = o e-mail ja existe (outra linha ja gravada); nada foi inserido. */
    fun insert(account: StoredAccount): Boolean

    fun updatePasswordHash(id: String, passwordHash: String): Boolean
}

class PostgresEmailAccountPersistence(private val db: DataSource) : EmailAccountPersistence {
    override fun loadAll(): List<EmailAccountPersistence.StoredAccount> = db.connection.use { c ->
        c.prepareStatement("SELECT id, email, senha_hash, nome, criada_ms FROM lobby.conta_email").executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(EmailAccountPersistence.StoredAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
                }
            }
        }
    }

    override fun insert(account: EmailAccountPersistence.StoredAccount): Boolean = db.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO lobby.conta_email (id, email, senha_hash, nome, criada_ms) VALUES (?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING",
        ).use { st ->
            st.setString(1, account.id)
            st.setString(2, account.email)
            st.setString(3, account.passwordHash)
            st.setString(4, account.name)
            st.setLong(5, account.createdMs)
            st.executeUpdate() == 1
        }
    }

    override fun updatePasswordHash(id: String, passwordHash: String): Boolean = db.connection.use { c ->
        c.prepareStatement("UPDATE lobby.conta_email SET senha_hash = ? WHERE id = ?").use { st ->
            st.setString(1, passwordHash)
            st.setString(2, id)
            st.executeUpdate() == 1
        }
    }
}

/**
 * Contas por e-mail e senha (§18.1, mudanca de 02/11/2026): cache em memoria, com copia duravel quando ha
 * [persistence] (sem ela, so memoria: desenvolvimento e testes rapidos). A senha nunca e guardada; so o hash bcrypt.
 * [passwordCost] baixo nos testes (bcrypt e lento de proposito) e o padrao (12) em produção.
 */
class EmailAccountRegistry(
    private val persistence: EmailAccountPersistence? = null,
    private val passwordCost: Int = 12,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // passwordHash e lido fora de bloco sincronizado em login(); volatile garante que o reset (noutra thread) seja visto.
    private class Entry(val id: String, val email: String, @Volatile var passwordHash: String, val name: String)

    private val byEmail = HashMap<String, Entry>()
    private val secure = SecureRandom()

    // §18.1: ao verificar login com e-mail inexistente, gasta o mesmo tempo de bcrypt que um e-mail real gastaria,
    // para o tempo de resposta nao revelar se a conta existe.
    private val dummyHash by lazy { PasswordHashing.hash("conta-inexistente-nao-usar", passwordCost) }

    init {
        persistence?.loadAll()?.forEach { stored ->
            byEmail[stored.email] = Entry(stored.id, stored.email, stored.passwordHash, stored.name)
        }
    }

    /**
     * §18.1 cadastro: e-mail unico (sem confirmacao), senha com no minimo 6 caracteres. @return (id, nome).
     * O bcrypt (CPU pesada de proposito) roda FORA do bloco sincronizado: so a leitura/escrita dos mapas trava,
     * para um cadastro lento nao travar os logins e cadastros de outras contas ao mesmo tempo.
     */
    fun register(rawEmail: String, rawPassword: String, rawName: String?): Pair<String, String> {
        val email = normalizeEmail(rawEmail) ?: lobbyError(ErrorCode.REQUISICAO_INVALIDA, "Informe um e-mail válido.", 400)
        validatePassword(rawPassword)
        synchronized(this) {
            if (byEmail.containsKey(email)) lobbyError(ErrorCode.EMAIL_JA_CADASTRADO, "Esse e-mail já tem uma conta.", 409)
        }
        val id = randomAccountId()
        val name = accountName(rawName)
        val hash = PasswordHashing.hash(rawPassword, passwordCost)
        val stored = EmailAccountPersistence.StoredAccount(id, email, hash, name, clock())
        synchronized(this) {
            if (byEmail.containsKey(email)) lobbyError(ErrorCode.EMAIL_JA_CADASTRADO, "Esse e-mail já tem uma conta.", 409)
            if (persistence?.insert(stored) == false) {
                // Corrida: outro pedido com o mesmo e-mail gravou primeiro no banco (outro pod, por exemplo).
                lobbyError(ErrorCode.EMAIL_JA_CADASTRADO, "Esse e-mail já tem uma conta.", 409)
            }
            byEmail[email] = Entry(id, email, hash, name)
        }
        return id to name
    }

    /**
     * §18.1 login: credenciais erradas e e-mail inexistente dao o mesmo erro, com o mesmo tempo de resposta. O
     * bcrypt roda fora do bloco sincronizado (so a leitura do mapa trava).
     */
    fun login(rawEmail: String, rawPassword: String): Pair<String, String> {
        val email = normalizeEmail(rawEmail)
        val entry = email?.let { synchronized(this) { byEmail[it] } }
        val ok = PasswordHashing.matches(rawPassword, entry?.passwordHash ?: dummyHash)
        if (entry == null || !ok) lobbyError(ErrorCode.NAO_AUTENTICADO, "E-mail ou senha incorretos.", 401)
        return entry.id to entry.name
    }

    /** Rota interna de reset (§18.1): gera e grava uma senha temporaria nova; `null` = conta inexistente. */
    fun resetPassword(rawEmail: String): String? {
        val email = normalizeEmail(rawEmail) ?: return null
        val entry = synchronized(this) { byEmail[email] } ?: return null
        val temp = TemporaryPassword.generate()
        val hash = PasswordHashing.hash(temp, passwordCost)
        persistence?.updatePasswordHash(entry.id, hash)
        synchronized(this) { entry.passwordHash = hash }
        return temp
    }

    private fun randomAccountId(): String {
        val bytes = ByteArray(20)
        secure.nextBytes(bytes)
        return AccountLimits.EMAIL_ID_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }
}
