package br.app.apagarfotos

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.annotation.RequiresApi
import java.util.Calendar

/** Uma foto ou vídeo da galeria. [mes] = ano * 100 + mês (ex.: 202611). */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val video: Boolean,
    val data: Long,
    val tamanho: Long,
    val pasta: String,
    val mes: Int,
)

enum class Acao { LIXEIRA, APAGAR, RESTAURAR }

object Galeria {

    private val COLUNAS = arrayOf(
        "_id", "datetaken", "date_modified", "date_added", "_size", "bucket_display_name",
    )

    /** Lê fotos e vídeos. Com [lixeira] = true, lê só o que está na lixeira (Android 11+). */
    fun carregar(ctx: Context, lixeira: Boolean = false): List<MediaItem> {
        val saida = ArrayList<MediaItem>()
        consultar(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, lixeira, saida)
        consultar(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, lixeira, saida)
        saida.sortByDescending { it.data }
        return saida
    }

    private fun consultar(
        ctx: Context, base: Uri, video: Boolean, lixeira: Boolean, saida: MutableList<MediaItem>,
    ) {
        val cr = ctx.contentResolver
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (lixeira) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE,
                )
            }
            cr.query(base, COLUNAS, args, null)
        } else {
            if (lixeira) return
            cr.query(base, COLUNAS, null, null, null)
        }
        val cal = Calendar.getInstance()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                // Data da foto; se não tiver (ex.: WhatsApp), usa a data do arquivo.
                var data = if (c.isNull(1)) 0L else c.getLong(1)
                if (data <= 0) data = c.getLong(2) * 1000
                if (data <= 0) data = c.getLong(3) * 1000
                cal.timeInMillis = data
                saida += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    video = video,
                    data = data,
                    tamanho = c.getLong(4),
                    pasta = c.getString(5) ?: "(sem pasta)",
                    mes = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH) + 1,
                )
            }
        }
    }

    /** Pede ao Android a confirmação para apagar / mandar para a lixeira / restaurar. */
    @RequiresApi(30)
    fun pedido(ctx: Context, uris: List<Uri>, acao: Acao): IntentSender {
        val cr = ctx.contentResolver
        return when (acao) {
            Acao.LIXEIRA -> MediaStore.createTrashRequest(cr, uris, true)
            Acao.RESTAURAR -> MediaStore.createTrashRequest(cr, uris, false)
            Acao.APAGAR -> MediaStore.createDeleteRequest(cr, uris)
        }.intentSender
    }

    /** Android 10 ou anterior: apaga direto (não existe lixeira). Devolve quantos apagou. */
    fun apagarDireto(ctx: Context, uris: List<Uri>): Int {
        var n = 0
        for (u in uris) {
            try {
                n += ctx.contentResolver.delete(u, null, null)
            } catch (_: Exception) {
            }
        }
        return n
    }

    // ---- Miniaturas ----

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    fun miniaturaEmCache(item: MediaItem): Bitmap? = cache.get(item.id)

    @Suppress("DEPRECATION")
    fun miniatura(ctx: Context, item: MediaItem): Bitmap? {
        cache.get(item.id)?.let { return it }
        val bmp = try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
            } else if (item.video) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    ctx.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null,
                )
            }
        } catch (_: Exception) {
            null
        }
        if (bmp != null) cache.put(item.id, bmp)
        return bmp
    }
}
