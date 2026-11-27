package com.smartstockmobile

import com.facebook.react.modules.network.OkHttpClientFactory
import com.facebook.react.modules.network.OkHttpClientProvider
import okhttp3.OkHttpClient
import java.io.IOException

/** HTTP is local-only. HTTPS keeps the standard platform trust and hostname checks. */
class LocalHttpClientFactory : OkHttpClientFactory {
  override fun createNewNetworkModuleClient(): OkHttpClient =
    OkHttpClientProvider.createClientBuilder()
      .addInterceptor { chain ->
        checkLocalHttp(chain.request().url.scheme, chain.request().url.host)
        chain.proceed(chain.request())
      }
      .addNetworkInterceptor { chain ->
        // Also checks redirects, before sending the redirected request.
        checkLocalHttp(chain.request().url.scheme, chain.request().url.host)
        chain.proceed(chain.request())
      }.build()

  private fun checkLocalHttp(scheme: String, host: String) {
    if (scheme != "http") return
    val parts = host.split('.')
    val numbers = parts.map { it.toIntOrNull() }
    val valid = parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) } &&
      numbers.all { it != null && it in 0..255 }
    val local = valid && (numbers[0] == 10 || numbers[0] == 127 ||
      (numbers[0] == 192 && numbers[1] == 168) ||
      (numbers[0] == 172 && numbers[1]!! in 16..31))
    if (!local) throw IOException("HTTP reserve aux adresses IPv4 locales privees ou loopback. Utiliser HTTPS ailleurs.")
  }
}
