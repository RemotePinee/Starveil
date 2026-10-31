package com.starveil.feature.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.starveil.core.hongguo.Mp4SampleTable
import com.starveil.core.hongguo.Region

/**
 * 把解密套在上游 DataSource 外面：读到的密文字节就地解成明文再交给 Media3。
 *
 * 只对加密片源构造，普通片源不会走到这里（见 [HongguoPlaying]）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HongguoCencDataSource(
    private val upstream: DataSource,
    private val key: ByteArray,
    private val table: Mp4SampleTable,
) : DataSource {

    private var position: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            table.decryptRange(Region(buffer, offset, n), position, key)
            position += n
        }
        return n
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
