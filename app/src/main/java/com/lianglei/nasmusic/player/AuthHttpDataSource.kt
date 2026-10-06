package com.lianglei.nasmusic.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.lianglei.nasmusic.data.FnApi

/**
 * Wraps DefaultHttpDataSource.Factory and injects the Feiniu Authorization
 * header on every createDataSource() call. Reads FnApi.token live, so it
 * works even if the service was created before login.
 */
@UnstableApi
class AuthHttpDataSourceFactory : DataSource.Factory {
    private val delegate = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("NasMusic/0.5")

    override fun createDataSource(): DataSource {
        if (FnApi.token.isNotEmpty()) {
            delegate.setDefaultRequestProperties(mapOf("Authorization" to FnApi.token))
        }
        return delegate.createDataSource()
    }
}
