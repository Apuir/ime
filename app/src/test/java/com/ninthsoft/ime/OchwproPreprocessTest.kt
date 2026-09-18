package com.ninthsoft.ime

import com.ninthsoft.ime.input.handwriting.HwStroke
import com.ninthsoft.ime.input.handwriting.ochwpro.OchwproPreprocess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [OchwproPreprocess] 的回归测试。
 *
 * 这一层是「错一点就整体掉准确率、而从报错里完全看不出来」的典型：
 * 张量形状对得上、推理正常返回，只是识别结果全错。所以每条约定都单独立一条用例钉住，
 * 尤其是那几条**与会直觉的相反**的（逐轴而非等比归一化、包围盒在截断前算）。
 */
class OchwproPreprocessTest {

    private val len = OchwproPreprocess.FIXED_LEN
    private val dim = OchwproPreprocess.FEATURE_DIM

    private fun HwStroke.firstX() = points[0]

    /** 取第 i 个点的第 d 个特征。 */
    private fun feat(values: FloatArray, i: Int, d: Int) = values[i * dim + d]

    // ---------------- simplify ----------------

    @Test
    fun `simplify 不超过上限的笔原样返回不做插值`() {
        val stroke = HwStroke(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val result = OchwproPreprocess.simplify(listOf(stroke))
        assertEquals(1, result.size)
        // 同一个数组引用即可：没有改动就不该产生新对象
        assertTrue(result[0] === stroke)
    }

    @Test
    fun `simplify 恰好等于上限时也不动`() {
        val points = FloatArray(OchwproPreprocess.MAX_POINTS_PER_STROKE * 2) { it.toFloat() }
        val stroke = HwStroke(points)
        val result = OchwproPreprocess.simplify(listOf(stroke))
        assertTrue(result[0] === stroke)
    }

    @Test
    fun `simplify 超长笔重采样到上限且保留首末点`() {
        // 9 个点，step = 8/7 = 1.142857，索引取整后为 [0,1,2,3,5,6,7,8]
        val points = FloatArray(9 * 2) { index ->
            if (index % 2 == 0) (index / 2).toFloat() else (index / 2).toFloat() * 10f
        }
        val result = OchwproPreprocess.simplify(listOf(HwStroke(points)))
        val simplified = result[0]
        assertEquals(OchwproPreprocess.MAX_POINTS_PER_STROKE, simplified.pointCount)
        // 首末点必须保留：它们决定笔画的起止位置，丢了就是缺头少尾
        assertEquals(0f, simplified.firstX(), 0f)
        assertEquals(8f, simplified.points[simplified.points.size - 2], 0f)
    }

    @Test
    fun `simplify 同步重采样时间戳`() {
        val points = FloatArray(20 * 2) { it.toFloat() }
        val times = LongArray(20) { it * 100L }
        val result = OchwproPreprocess.simplify(listOf(HwStroke(points, times)))
        val simplifiedTimes = result[0].times
        assertEquals(OchwproPreprocess.MAX_POINTS_PER_STROKE, simplifiedTimes?.size)
        // 只取原数组里的值，不插值产生新时刻
        for (t in simplifiedTimes!!) {
            assertTrue("时间戳 $t 不在原序列里", t % 100L == 0L && t in 0L..1900L)
        }
    }

    // ---------------- build：基本形状 ----------------

    @Test
    fun `build 空笔迹得到全零与长度为 0`() {
        val result = OchwproPreprocess.build(emptyList())
        assertEquals(0, result.length)
        assertEquals(len * dim, result.values.size)
        assertEquals(len, result.mask.size)
        assertTrue("掩码应全零", result.mask.all { it == 0.toByte() })
        assertTrue("特征应全零", result.values.all { it == 0f })
    }

    @Test
    fun `build 只含空笔时等同于空笔迹`() {
        val result = OchwproPreprocess.build(
            listOf(HwStroke(floatArrayOf()), HwStroke(floatArrayOf()))
        )
        assertEquals(0, result.length)
        assertTrue(result.values.all { it == 0f })
    }

    @Test
    fun `build 张量尺寸固定不随输入变化`() {
        // 这是模型的硬约束：定长 200 步、每步 5 维。任何输入都必须得到同样大小
        val single = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(1f, 1f))))
        assertEquals(len * dim, single.values.size)
        assertEquals(len, single.mask.size)

        val many = OchwproPreprocess.build(
            List(5) { HwStroke(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f)) }
        )
        assertEquals(len * dim, many.values.size)
        assertEquals(len, many.mask.size)
    }

    // ---------------- build：归一化 ----------------

    @Test
    fun `build 用全局包围盒填补到 0 到 1`() {
        // 包围盒 x 10..30、y 20..60（不含）
        val stroke = HwStroke(floatArrayOf(10f, 20f, 30f, 60f))
        val result = OchwproPreprocess.build(listOf(stroke))
        assertEquals(2, result.length)
        assertEquals(0f, feat(result.values, 0, 0), 1e-5f)
        assertEquals(0f, feat(result.values, 0, 1), 1e-5f)
        assertEquals(1f, feat(result.values, 1, 0), 1e-5f)
        assertEquals(1f, feat(result.values, 1, 1), 1e-5f)
    }

    @Test
    fun `build 归一化是逐轴独立而不是等比`() {
        // 这个模型训练时就用的逐轴归一化：x 除以 x 跨度、y 除以 y 跨度。
        // 直觉上「保形」更合理，但模型只认它训练时见过的东西 —— 改成等比就是错的。
        // 用一个 4:1 的扁平笔迹验证：两侧跨度都被各自拉满，即非等比。
        val stroke = HwStroke(floatArrayOf(0f, 0f, 40f, 10f))
        val result = OchwproPreprocess.build(listOf(stroke))
        assertEquals(1f, feat(result.values, 1, 0), 1e-5f)
        assertEquals(1f, feat(result.values, 1, 1), 1e-5f)
    }

    @Test
    fun `build 包围盒跨笔而不是逐笔`() {
        // 第一笔在左上角一小团、第二笔在右下角一小团。
        // 若逐笔归一化，两笔会各自撑满、笔画间的相对位置彻底丢失 ——
        // 而「部件相对整字的位置和大小」正是识别要用的信息。
        val first = HwStroke(floatArrayOf(0f, 0f, 10f, 10f))
        val second = HwStroke(floatArrayOf(90f, 90f, 100f, 100f))
        val result = OchwproPreprocess.build(listOf(first, second))

        // 全局包围盒是 0..100，所以第一笔落在靠近 0 的位置、第二笔靠近 1
        assertTrue("第一笔应靠近原点", feat(result.values, 0, 0) < 0.15f)
        assertTrue("第二笔应靠近 1", feat(result.values, 3, 0) > 0.85f)
    }

    @Test
    fun `build 跨度为 0 的退化笔迹不会产生 NaN 或 Inf`() {
        // 单点：x、y 跨度都是 0；水平线：y 跨度是 0。
        // 分母若取 0 就得到 NaN，整个序列随之报废，而模型只会给出无意义的候选。
        val single = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(5f, 5f))))
        assertTrue("单点不应产生 NaN", single.values.none { it.isNaN() })
        assertTrue("单点不应产生 Inf", single.values.all { it.isFinite() })

        val horizontal = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(0f, 50f, 80f, 50f))))
        assertTrue(horizontal.values.all { it.isFinite() })
        assertTrue(horizontal.values.none { it.isNaN() })
    }

    // ---------------- build：差分与 penDown ----------------

    @Test
    fun `build 第一个点的差分为 0`() {
        val stroke = HwStroke(floatArrayOf(10f, 20f, 30f, 60f))
        val result = OchwproPreprocess.build(listOf(stroke))
        assertEquals(0f, feat(result.values, 0, 2), 1e-6f)
        assertEquals(0f, feat(result.values, 0, 3), 1e-6f)
    }

    @Test
    fun `build 差分为相邻点之差除以相应跨度`() {
        // 包围盒 x 0..100、y 0..100；两点 (0,0) -> (25,50)
        // dx = 25/100 = 0.25，dy = 50/100 = 0.5
        val stroke = HwStroke(floatArrayOf(0f, 0f, 25f, 50f, 100f, 100f))
        val result = OchwproPreprocess.build(listOf(stroke))
        assertEquals(0.25f, feat(result.values, 1, 2), 1e-5f)
        assertEquals(0.5f, feat(result.values, 1, 3), 1e-5f)
        // 第二段：75/100 = 0.75，50/100 = 0.5
        assertEquals(0.75f, feat(result.values, 2, 2), 1e-5f)
        assertEquals(0.5f, feat(result.values, 2, 3), 1e-5f)
    }

    @Test
    fun `build 只有每一笔的末点标记抬笔`() {
        // 两笔各 2 点 → 4 个点，抬笔标志应出现在下标 1 和 3
        val first = HwStroke(floatArrayOf(0f, 0f, 10f, 10f))
        val second = HwStroke(floatArrayOf(20f, 0f, 30f, 10f))
        val result = OchwproPreprocess.build(listOf(first, second))

        assertEquals(4, result.length)
        assertEquals(1f, feat(result.values, 0, 4), 0f)
        assertEquals(0f, feat(result.values, 1, 4), 0f)
        assertEquals(1f, feat(result.values, 2, 4), 0f)
        assertEquals(0f, feat(result.values, 3, 4), 0f)
    }

    @Test
    fun `build 单笔单点的抬笔标志为 0`() {
        // 只有一个点，它同时是首点也是末点
        val result = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(5f, 5f))))
        assertEquals(1, result.length)
        assertEquals(0f, feat(result.values, 0, 4), 0f)
    }

    // ---------------- build：掩码与截断 ----------------

    @Test
    fun `build 掩码前 length 位为 1 其余为 0`() {
        val stroke = HwStroke(floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f))
        val result = OchwproPreprocess.build(listOf(stroke))
        assertEquals(3, result.length)
        for (i in 0 until len) {
            val expected = if (i < 3) 1.toByte() else 0.toByte()
            assertEquals("掩码第 $i 位", expected, result.mask[i])
        }
    }

    @Test
    fun `build 超过定长的点被截断且掩码拉满`() {
        // 每笔被 simplify 限制到 8 点，所以 26 笔 × 8 点 = 208 点才会触发截断
        val strokes = List(26) { index ->
            val base = index * 100f
            HwStroke(
                FloatArray(OchwproPreprocess.MAX_POINTS_PER_STROKE * 2) { i ->
                    if (i % 2 == 0) base + i else base + i
                }
            )
        }
        val result = OchwproPreprocess.build(strokes)
        assertEquals(len, result.length)
        assertTrue("掩码应全为 1", result.mask.all { it == 1.toByte() })
        // 第 200 步（下标 199）必须确实写入了数据，而不是被提前截断成零
        assertTrue("截断处应有实际数据", result.values[(len - 1) * dim] != 0f)
    }

    @Test
    fun `build 截断后未使用的尾部保持全零`() {
        val result = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(0f, 0f, 10f, 10f))))
        // 前 2 步有数据，其余必须严格为 0
        for (i in 2 until len) {
            for (d in 0 until dim) {
                assertEquals("尾部第 $i 步第 $d 维应为 0", 0f, feat(result.values, i, d), 0f)
            }
        }
    }

    @Test
    fun `build 归一化后的坐标始终落在 0 到 1`() {
        val strokes = listOf(
            HwStroke(floatArrayOf(120f, 300f, 150f, 260f, 200f, 240f)),
            HwStroke(floatArrayOf(230f, 300f, 260f, 200f)),
        )
        val result = OchwproPreprocess.build(strokes)
        for (i in 0 until result.length) {
            for (d in 0..1) {
                val value = feat(result.values, i, d)
                assertTrue("第 $i 步第 $d 维越界：$value", value in -1e-4f..1.0001f)
            }
        }
    }

    @Test
    fun `build 输入坐标整体平移不改变结果`() {
        // 包围盒归一化会减掉最小值，所以「手写区偏了多少像素」理论上完全不影响特征。
        // 这条同时钉住了「不需要外部再对齐原点」这个结论。
        val a = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(0f, 0f, 30f, 60f))))
        val b = OchwproPreprocess.build(listOf(HwStroke(floatArrayOf(500f, 777f, 530f, 837f))))
        assertEquals(a.length, b.length)
        for (i in 0 until a.length * dim) {
            assertTrue("第 $i 项差 ${abs(a.values[i] - b.values[i])}", abs(a.values[i] - b.values[i]) < 1e-4f)
        }
    }
}
