package com.gtranca.online.lobby

import com.gtranca.online.contracts.TableId
import java.net.URLEncoder

/** Pagina do convite (§18.2): link https://<servidor>/m/<id>. HTML puro em pt-BR; entrada sempre validada e escapada. */
object InvitePage {
    private val HOST_REGEX = Regex("^[A-Za-z0-9.\\-]{1,253}(:[0-9]{1,5})?$")

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Host do cabecalho Host, ou null se tiver formato estranho (nao refletimos lixo). */
    fun safeHost(rawHost: String?): String? = rawHost?.trim()?.takeIf { HOST_REGEX.matches(it) }

    fun deepLink(id: String, host: String?): String =
        "tranca://mesa/$id" + if (host != null) "?s=" + URLEncoder.encode(host, Charsets.UTF_8) else ""

    fun render(rawId: String, rawHost: String?): Pair<Int, String> {
        val id = TableId.normalize(rawId)
            ?: return 404 to page("Convite inválido", "<p class=\"msg\">Esse convite não é válido. Confira o link ou peça o id da mesa a quem te convidou.</p>")
        val link = escape(deepLink(id, safeHost(rawHost)))
        val body = """
            <p class="lead">Você foi convidado para uma mesa</p>
            <div class="id" aria-label="Id da mesa">$id</div>
            <a id="open" class="btn" href="$link">Abrir no Tranca-Bot</a>
            <p class="msg">Ou abra o app, configure o servidor e digite o id.</p>
            <p class="warn">É preciso ter o aplicativo Tranca-Bot instalado neste aparelho.</p>
            <script>
              setTimeout(function () { window.location.href = document.getElementById('open').href; }, 600);
            </script>
        """.trimIndent()
        return 200 to page("Convite para a mesa $id", body)
    }

    private fun page(title: String, body: String) = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex">
          <title>${escape(title)}</title>
          <style>
            body { font-family: system-ui, sans-serif; background: #1d3f8f; color: #fdf6e3; margin: 0; padding: 24px; text-align: center; }
            h1 { color: #f5c542; letter-spacing: 2px; font-size: 1.3rem; }
            .id { font-size: 3rem; font-weight: 800; letter-spacing: 8px; margin: 16px 0; color: #f5c542; }
            .btn { display: inline-block; background: #f5c542; color: #1d3f8f; font-weight: 700; padding: 16px 28px; border-radius: 12px; text-decoration: none; font-size: 1.1rem; }
            .msg, .lead { margin-top: 20px; }
            .warn { margin-top: 12px; font-size: .9rem; opacity: .85; }
          </style>
        </head>
        <body>
          <h1>TRANCA-BOT</h1>
          $body
        </body>
        </html>
    """.trimIndent()
}
