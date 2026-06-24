package com.ninthsoft.ime.engine.ranking

import android.content.Context
import com.ninthsoft.ime.base.util.appContext
import java.io.File

class BGEReranker : IReranker {

    private val embedder = BGEEmbedder()

    override fun onCreate(context: Context) {
        val dir = File(appContext.getExternalFilesDir(null), "rerank").also { it.mkdirs() }
        val modelFile = File(dir, "model_quantized.onnx")
        val tokenizerFile = File(dir, "tokenizer.json")
        embedder.load(modelFile.absolutePath, tokenizerFile.absolutePath)
    }

    override fun onDestroy() {
        embedder.close()
    }

    override fun rerank(query: String, documents: List<String>): List<IReranker.Result> {
        val queryVec = embedder.encode(query)
        return documents.map { doc ->
            val docVec = embedder.encode(doc)
            IReranker.Result(doc, embedder.similarity(queryVec, docVec))
        }.sortedByDescending { it.score }
    }
}
