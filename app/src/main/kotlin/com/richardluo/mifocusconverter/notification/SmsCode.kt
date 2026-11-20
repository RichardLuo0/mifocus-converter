package com.richardluo.mifocusconverter.notification

import kotlin.math.abs

/** 从短信文案里提取验证码 */
object SmsCode {
  private const val KEYWORD = "验证码"
  private const val PREFERRED_LENGTH = 6

  private val DIGITS = Regex("""\d{4,8}""")

  /** 只要有文本包含「验证码」，就取里面最像验证码的数字串： 不关心和关键字的位置关系，多个候选时取长度最接近 6 位的。 */
  fun extract(texts: List<CharSequence>): String? = texts.firstNotNullOfOrNull { text ->
    val content = text.toString()
    if (!content.contains(KEYWORD)) return@firstNotNullOfOrNull null
    DIGITS.findAll(content).map { it.value }.minByOrNull { abs(it.length - PREFERRED_LENGTH) }
  }
}
