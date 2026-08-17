package com.ninthsoft.ime.engine.manager

import android.content.Context
import com.ninthsoft.ime.base.marisa.Prediction
import com.ninthsoft.ime.base.ngram.GramDb
import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import java.io.File

class PredictionManager(private val context: Context) {
    private var prediction: Prediction? = null
    private var gramDb: GramDb? = null
    private val calculator = PriorityCalculator()

    suspend fun loadModels(modelDir: File, sharedDataDir: File, language: String?) {
        if (language.isNullOrEmpty()) return
        val gram = File(sharedDataDir, "$language.gram")
        if (gram.isFile) {
            gramDb = GramDb(gram.absolutePath)
            val predictGram = File(modelDir, "predict.marisa")
            if (predictGram.isFile) {
                prediction = Prediction(predictGram).apply { load() }
            }
        }
    }

    fun destroy() {
        prediction?.destroy()
        prediction = null
        gramDb = null
    }

    suspend fun makePredictions(inputContext: String): List<Candidate> {
        val pred = prediction ?: return emptyList()
        val possiables = TextUtil.contextSubstrings(inputContext)
        val cfg = WeightConfig()

        for (contextStr in possiables) {
            if (contextStr.isEmpty()) continue
            val words = pred.predictNextWords(contextStr)
            if (words.size >= 5) {
                val texts = words.map { it.word }
                val prefers =
                    AppDatabase.getInstance(context).candidatePreferDao().getAllByTextIn(texts)
                        .associate { it.text to it.count }

                val candidates = words.mapIndexed { index, it ->
                    val gramScore = gramDb?.query(inputContext, it.word) ?: 0.0
                    val preferCount = prefers[it.word] ?: 0
                    val textLen = it.word.codePointCount(0, it.word.length)
                    val score = calculator.calculate(
                        CandidateFeature(
                            frequency = preferCount.toLong(),
                            wordLength = textLen,
                            candidateCount = 1,
                            baseScore = gramScore
                        ), cfg
                    )
                    Candidate(
                        index = index,
                        text = it.word,
                        type = Candidate.CandidateType.Prediction,
                        score = score
                    )
                }.sortedByDescending { it.score }

                return candidates.take(25)
            }
        }
        return emptyList()
    }
}
