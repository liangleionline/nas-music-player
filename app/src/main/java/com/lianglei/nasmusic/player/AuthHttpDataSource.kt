package com.lianglei.nasmusic.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.lianglei.nasmusic.data.FnApi

/**
 * Wraps a DefaultHttpDataSource and adds the Authorization header
 * (raw token, no Bearer prefix) for any URL under the Feiniu music API.
 * Local file URLs are passed through untouched.
 */
@UnstableApi
class AuthHttpDataSource(
    private val upstream: DefaultHttpDataSource = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()
) : DataSource by upstream {

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        if (FnApi.token.isNotEmpty() && url.startsWith("http") && url.contains("/music/api/v1/")) {
            val headers = buildMap {
                putAll(dataSpec.httpRequestHeaders)
                put("Authorization", FnApi.token)
            }
            val modified = dataSpec.buildUpon()
                .setHttpRequestHeaders(headers)
                .build()
            return upstream.open(modified)
        }
        return upstream.open(dataSpec)
    }

    companion object {
        fun factory(): DataSource.Factory = object : DataSource.Factory {
            override fun createDataSource(): DataSource = AuthHttpDataSource()
        }
    }
}
