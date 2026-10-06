package com.lianglei.nasmusic.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.lianglei.nasmusic.data.FnApi
import com.lianglei.nasmusic.util.CrashLogger

/**
 * Wraps DefaultHttpDataSource and logs the HTTP response status and content
 * length after open(). This tells us if the stream request completes at all.
 */
@UnstableApi
class AuthHttpDataSourceFactory : DataSource.Factory {
    private val delegate = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("NasMusic/0.5")

    override fun createDataSource(): DataSource {
        CrashLogger.log("AuthFactory.createDataSource, token=${if (FnApi.token.isNotEmpty()) FnApi.token.take(8) else "EMPTY"}")
        if (FnApi.token.isNotEmpty()) {
            delegate.setDefaultRequestProperties(mapOf("Authorization" to FnApi.token))
        }
        val ds = delegate.createDataSource() as DefaultHttpDataSource
        return object : HttpDataSource by ds {
            override fun open(dataSpec: DataSpec): Long {
                CrashLogger.log("AuthDS.open: ${dataSpec.uri}")
                return try {
                    val bytes = ds.open(dataSpec)
                    CrashLogger.log("AuthDS.open OK, bytes=$bytes, responseCode=${ds.responseCode}")
                    bytes
                } catch (e: Exception) {
                    CrashLogger.e("AuthDS.open FAILED: ${e.message}", e)
                    throw e
                }
            }
            override fun close() { ds.close() }
        }
    }
}
